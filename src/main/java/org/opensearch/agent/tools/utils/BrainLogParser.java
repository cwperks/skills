/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.agent.tools.utils;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Log parser Brain algorithm implementation. See: https://ieeexplore.ieee.org/document/10109145
 */
public class BrainLogParser {

    private static final String VARIABLE_DENOTER = "<*>";
    private static final Map<Pattern, String> DEFAULT_FILTER_PATTERN_VARIABLE_MAP = new LinkedHashMap<>();
    static {
        // IP
        DEFAULT_FILTER_PATTERN_VARIABLE_MAP.put(Pattern.compile("(/|)([0-9]+\\.){3}[0-9]+(:[0-9]+|)(:|)"), "<*IP*>");
        // Simple ISO date and time
        DEFAULT_FILTER_PATTERN_VARIABLE_MAP
            .put(Pattern.compile("(\\d{4}-\\d{2}-\\d{2})[T ]?(\\d{2}:\\d{2}:\\d{2})(\\.\\d{3})?(Z|([+-]\\d{2}:?\\d{2}))?"), "<*DATETIME*>");
        // Hex Decimal, letters followed by digits, float numbers, 4 or more digits in case of leaving 3 digit response code alone
        DEFAULT_FILTER_PATTERN_VARIABLE_MAP
            .put(Pattern.compile("((0x|0X)[0-9a-fA-F]+)|[a-zA-Z]+\\d+|([+-]?(?!\\d{3}$)\\d{4,}(\\.\\d*)?|\\.\\d+)"), VARIABLE_DENOTER);
        // generic number surrounded by non-alphanumeric
        DEFAULT_FILTER_PATTERN_VARIABLE_MAP.put(Pattern.compile("(?<=[^A-Za-z0-9 ])(-?\\+?\\d+)(?=[^A-Za-z0-9])"), VARIABLE_DENOTER);
    }
    private static final List<String> DEFAULT_DELIMITERS = List.of(",", "+");
    // counting frequency will be grouped by composite of position and token string
    private static final String POSITIONED_TOKEN_KEY_FORMAT = "%d-%s";
    // Token set will be grouped by composite of tokens length per log message, word combination candidate and token position.
    private static final String GROUP_TOKEN_SET_KEY_FORMAT = "%d-%s-%d";
    // By default, algorithm treats more than 2 different tokens in the group per position as variable token
    private static final int DEFAULT_VARIABLE_COUNT_THRESHOLD = 5;
    /*
     * By default, algorithm treats the longest word combinations as the group root, no matter what its frequency is.
     * Otherwise, the longest word combination will be selected when frequency >= highest frequency of log * threshold percentage
     */
    private static final float DEFAULT_FREQUENCY_THRESHOLD_PERCENTAGE = 0.3f;

    private final Map<String, Long> tokenFreqMap;
    private final Map<String, Set<String>> groupTokenSetMap;
    private final Map<String, String> logIdGroupCandidateMap;
    private final int variableCountThreshold;
    private final float thresholdPercentage;
    private final Map<Pattern, String> filterPatternVariableMap;
    private final List<String> delimiters;

    /**
     * Creates new Brain log parser with default parameters
     */
    public BrainLogParser() {
        this(
            DEFAULT_VARIABLE_COUNT_THRESHOLD,
            DEFAULT_FREQUENCY_THRESHOLD_PERCENTAGE,
            DEFAULT_FILTER_PATTERN_VARIABLE_MAP,
            DEFAULT_DELIMITERS
        );
    }

    /**
     * Creates new Brain log parser with overridden variableCountThreshold and thresholdPercentage
     * @param variableCountThreshold the threshold to decide whether low frequency token is variable
     * @param thresholdPercentage the threshold percentage to decide which frequency is representative
     *                            frequency per log message
     */
    public BrainLogParser(int variableCountThreshold, float thresholdPercentage) {
        this(variableCountThreshold, thresholdPercentage, DEFAULT_FILTER_PATTERN_VARIABLE_MAP, DEFAULT_DELIMITERS);
    }

    /**
     * Creates new Brain log parser with overridden filter patterns and delimiters
     * @param filterPatternVariableMap a map of regex patterns to variable denoter, with which the matched pattern will be replaced,
     *                                 recommend to use LinkedHashMap to make sure patterns in order
     * @param delimiters a list of delimiters to be replaced with empty string after regex replacement
     */
    public BrainLogParser(Map<Pattern, String> filterPatternVariableMap, List<String> delimiters) {
        this(DEFAULT_VARIABLE_COUNT_THRESHOLD, DEFAULT_FREQUENCY_THRESHOLD_PERCENTAGE, filterPatternVariableMap, delimiters);
    }

    /**
     * Creates new Brain log parser with overridden variableCountThreshold and thresholdPercentage and
     * overridden filter patterns and delimiters
     * @param variableCountThreshold the threshold to decide whether low frequency token is variable
     * @param thresholdPercentage the threshold percentage to decide which frequency is representative
     *                            frequency per log message
     * @param filterPatternVariableMap a map of regex patterns to variable denoter, with which the matched pattern will be replaced,
     *                                 recommend to use LinkedHashMap to make sure patterns in order
     * @param delimiters a list of delimiters to be replaced with empty string after regex replacement
     */
    public BrainLogParser(
        int variableCountThreshold,
        float thresholdPercentage,
        Map<Pattern, String> filterPatternVariableMap,
        List<String> delimiters
    ) {
        if (thresholdPercentage < 0.0f || thresholdPercentage > 1.0f) {
            throw new IllegalArgumentException("Threshold percentage must be between 0.0 and 1.0");
        }
        this.tokenFreqMap = new HashMap<>();
        this.groupTokenSetMap = new HashMap<>();
        this.logIdGroupCandidateMap = new HashMap<>();
        this.variableCountThreshold = variableCountThreshold;
        this.thresholdPercentage = thresholdPercentage;
        this.filterPatternVariableMap = filterPatternVariableMap;
        this.delimiters = delimiters;
    }

    /**
     * Preprocess single line of log message with logId
     * @param logMessage log message body per log
     * @param logId logId of the log
     * @return list of tokens by splitting preprocessed log message
     */
    public List<String> preprocess(String logMessage, String logId) {
        if (logMessage == null || logId == null) {
            throw new IllegalArgumentException("log message or logId must not be null");
        }
        // match regex and replace it with variable denoter in order
        for (Map.Entry<Pattern, String> patternVariablePair : filterPatternVariableMap.entrySet()) {
            logMessage = patternVariablePair.getKey().matcher(logMessage).replaceAll(patternVariablePair.getValue());
        }

        for (String delimiter : delimiters) {
            logMessage = logMessage.replace(delimiter, " ");
        }

        // Append logId/docId to the end of the split tokens
        logMessage = logMessage.trim() + " " + logId;

        return Arrays.asList(logMessage.split("\\s+"));
    }

    /**
     * Count token frequency per position/index in the token list
     * @param tokens list of tokens from preprocessed log message
     */
    public void processTokenHistogram(List<String> tokens) {
        // Ignore last element since it's designed to be appended logId
        for (int i = 0; i < tokens.size() - 1; i++) {
            String tokenKey = String.format(Locale.ROOT, POSITIONED_TOKEN_KEY_FORMAT, i, tokens.get(i));
            tokenFreqMap.compute(tokenKey, (k, v) -> v == null ? 1 : v + 1);
        }
    }

    /**
     * Preprocess all lines of log messages with logId list. Empty logId list is allowed as the index within
     * the list will be logId by default
     * @param logMessages list of log messages
     * @return list of token lists
     */
    public List<List<String>> preprocessAllLogs(List<String> logMessages) {
        List<List<String>> preprocessedLogs = new ArrayList<>();

        for (int i = 0; i < logMessages.size(); i++) {
            String logId = String.valueOf(i);
            List<String> tokens = this.preprocess(logMessages.get(i), logId);
            preprocessedLogs.add(tokens);
            this.processTokenHistogram(tokens);
        }

        return preprocessedLogs;
    }

    /**
     * The second process step to calculate initial groups of tokens based on previous token histogram.
     * The group will be represented by the representative word combination of the log message. The word
     * combination usually selects the longest word combination with the same frequency that should be above
     * designed threshold.
     * <p>
     * Within initial group, new group level token set per position is counted for final log pattern calculation
     * @param preprocessedLogs preprocessed list of log messages
     */
    public void calculateGroupTokenFreq(List<List<String>> preprocessedLogs) {
        for (List<String> tokens : preprocessedLogs) {
            Map<Long, Integer> wordOccurrences = this.getWordOccurrences(tokens);
            List<WordCombination> sortedWordCombinations = wordOccurrences
                .entrySet()
                .stream()
                .map(entry -> new WordCombination(entry.getKey(), entry.getValue()))
                .sorted()
                .toList();
            WordCombination candidate = this.findCandidate(sortedWordCombinations);
            String groupCandidateStr = String.format(Locale.ROOT, "%d,%d", candidate.wordFreq(), candidate.sameFreqCount());
            this.logIdGroupCandidateMap.put(tokens.getLast(), groupCandidateStr);
            this.updateGroupTokenFreqMap(tokens, groupCandidateStr);
        }
    }

    /**
     * Parse single line of log pattern after preprocess - processTokenHistogram - calculateGroupTokenFreq
     * @param tokens list of tokens for a specific log message
     * @return parsed log pattern that is a list of string
     */
    public List<String> parseLogPattern(List<String> tokens) {
        String logId = tokens.getLast();
        String groupCandidateStr = this.logIdGroupCandidateMap.get(logId);
        String[] groupCandidate = groupCandidateStr.split(",");
        Long repFreq = Long.parseLong(groupCandidate[0]); // representative frequency of the group
        return IntStream.range(0, tokens.size() - 1).mapToObj(i -> new AbstractMap.SimpleEntry<>(i, tokens.get(i))).map(entry -> {
            int index = entry.getKey();
            String token = entry.getValue();
            String tokenKey = String.format(Locale.ROOT, POSITIONED_TOKEN_KEY_FORMAT, index, token);
            assert this.tokenFreqMap.get(tokenKey) != null : String.format(Locale.ROOT, "Not found token: %s on position %d", token, index);

            boolean isHigherFrequency = this.tokenFreqMap.get(tokenKey) > repFreq;
            boolean isLowerFrequency = this.tokenFreqMap.get(tokenKey) < repFreq;
            String groupTokenKey = String.format(Locale.ROOT, GROUP_TOKEN_SET_KEY_FORMAT, tokens.size() - 1, groupCandidateStr, index);
            assert this.groupTokenSetMap.get(groupTokenKey) != null : String
                .format(Locale.ROOT, "Not found any token in group: %s", groupTokenKey);

            if (isHigherFrequency) {
                // For higher frequency token that doesn't belong to word combination, it's likely to be constant token only if
                // it's unique token on that position within the group
                boolean isUniqueToken = this.groupTokenSetMap.get(groupTokenKey).size() == 1;
                if (!isUniqueToken) {
                    return VARIABLE_DENOTER;
                }
            } else if (isLowerFrequency) {
                // For lower frequency token that doesn't belong to word combination, it's likely to be constant token only if
                // it doesn't exceed the preset variable count threshold. For example, some variable are limited number of enums,
                // and sometimes they could be treated as constant tokens.
                if (this.groupTokenSetMap.get(groupTokenKey).size() >= variableCountThreshold) {
                    return VARIABLE_DENOTER;
                }
            }
            return token;
        }).collect(Collectors.toList());
    }

    /**
     * Parse all lines of log messages to generate the log pattern map.
     * @param logMessages all lines of log messages
     * @return log pattern map with log pattern string as key, grouped logIds as value
     */
    public Map<String, List<String>> parseAllLogPatterns(List<String> logMessages) {
        List<List<String>> processedMessages = this.preprocessAllLogs(logMessages);

        this.calculateGroupTokenFreq(processedMessages);

        Map<String, List<String>> logPatternMap = new HashMap<>();
        for (List<String> processedMessage : processedMessages) {
            String logId = processedMessage.getLast();
            List<String> logPattern = this.parseLogPattern(processedMessage);
            String patternKey = String.join(" ", logPattern);
            logPatternMap.computeIfAbsent(patternKey, k -> new ArrayList<>()).add(logId);
        }
        return logPatternMap;
    }

    /**
     * Get token histogram
     * @return map of token per position key and its frequency
     */
    public Map<String, Long> getTokenFreqMap() {
        return this.tokenFreqMap;
    }

    /**
     * Get group per length per position to its token set map
     * @return map of pattern group per length per position key and its token set
     */
    public Map<String, Set<String>> getGroupTokenSetMap() {
        return this.groupTokenSetMap;
    }

    /**
     * Get logId to its group candidate map
     * @return map of logId and group candidate
     */
    public Map<String, String> getLogIdGroupCandidateMap() {
        return this.logIdGroupCandidateMap;
    }

    private Map<Long, Integer> getWordOccurrences(List<String> tokens) {
        Map<Long, Integer> occurrences = new HashMap<>();
        for (int i = 0; i < tokens.size() - 1; i++) {
            String tokenKey = String.format(Locale.ROOT, POSITIONED_TOKEN_KEY_FORMAT, i, tokens.get(i));
            Long tokenFreq = tokenFreqMap.get(tokenKey);
            occurrences.compute(tokenFreq, (k, v) -> v == null ? 1 : v + 1);
        }
        return occurrences;
    }

    private WordCombination findCandidate(List<WordCombination> sortedWordCombinations) {
        if (sortedWordCombinations.isEmpty()) {
            throw new IllegalArgumentException("Sorted word combinations must be non empty");
        }
        OptionalLong maxFreqOptional = sortedWordCombinations.stream().mapToLong(WordCombination::wordFreq).max();
        long maxFreq = maxFreqOptional.getAsLong();
        float threshold = maxFreq * this.thresholdPercentage;
        for (WordCombination wordCombination : sortedWordCombinations) {
            if (wordCombination.wordFreq() > threshold) {
                return wordCombination;
            }
        }
        return sortedWordCombinations.getFirst();
    }

    private void updateGroupTokenFreqMap(List<String> tokens, String groupCandidateStr) {
        int tokensLen = tokens.size() - 1;
        for (int i = 0; i < tokensLen; i++) {
            String groupTokenFreqKey = String.format(Locale.ROOT, GROUP_TOKEN_SET_KEY_FORMAT, tokensLen, groupCandidateStr, i);
            this.groupTokenSetMap.computeIfAbsent(groupTokenFreqKey, k -> new HashSet<>()).add(tokens.get(i));
        }
    }

    private record WordCombination(Long wordFreq, Integer sameFreqCount) implements Comparable<WordCombination> {

        @Override
        public int compareTo(WordCombination other) {
            // Compare by same frequency count in descending order
            int wordFreqComparison = other.sameFreqCount.compareTo(this.sameFreqCount);
            if (wordFreqComparison != 0) {
                return wordFreqComparison;
            }

            // If sameFreqCount are the same, compare by wordFreq in descending order
            return other.wordFreq.compareTo(this.wordFreq);
        }
    }
}
