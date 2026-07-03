package fr.insalyon.creatis.gasw.plugin.listener.healing;

import fr.insalyon.creatis.gasw.plugin.listener.healing.execution.CommandState;
import fr.insalyon.creatis.gasw.plugin.listener.healing.execution.CommandState.Timings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

public class CommandStateTest {

    @Nested
    @DisplayName("computeMedians()")
    class ComputeMedians {

        @Test
        @DisplayName("returns zero for all fields when no data has been recorded")
        void emptyQueues_returnZero() {
            CommandState cs = new CommandState("cmd");
            Timings t = cs.computeMedians();
            assertEquals(0L, t.setup());
            assertEquals(0L, t.input());
            assertEquals(0L, t.execution());
            assertEquals(0L, t.output());
        }

        @ParameterizedTest(name = "values {0} -> median {1}")
        @DisplayName("computes the median for odd, even, and single-value sample sizes")
        @CsvSource({
                "10;30;20, 20",    // odd count -> middle value
                "10;20;30;40, 25", // even count -> average of middle two
                "42, 42"           // single value -> that value
        })
        void variousSampleSizes_returnCorrectMedian(String values, long expectedMedian) {
            CommandState cs = new CommandState("cmd");
            for (String v : values.split(";")) {
                cs.addSetupTime(Long.parseLong(v));
            }

            assertEquals(expectedMedian, cs.computeMedians().setup());
        }
    }

    @Nested
    @DisplayName("hasEnoughData()")
    class HasEnoughData {

        @Test
        @DisplayName("is false for zero or one recorded output time")
        void falseWhenZeroOrOneOutputTimes() {
            CommandState cs = new CommandState("cmd");
            assertFalse(cs.hasEnoughData());
            cs.addUploadTime(5);
            assertFalse(cs.hasEnoughData());
        }

        @Test
        @DisplayName("is true once more than one output time is recorded")
        void trueWhenMoreThanOneOutputTime() {
            CommandState cs = new CommandState("cmd");
            cs.addUploadTime(5);
            cs.addUploadTime(6);
            assertTrue(cs.hasEnoughData());
        }
    }

    @Nested
    @DisplayName("shouldLogTimings()")
    class ShouldLogTimings {

        @Test
        @DisplayName("is true on the first call, before anything has been logged")
        void trueOnFirstCall() {
            CommandState cs = new CommandState("cmd");
            assertTrue(cs.shouldLogTimings(new Timings(10, 10, 10, 10), 10));
        }

        @ParameterizedTest(name = "baseline {0}, new {1}, threshold {2}% should trigger {3}")
        @DisplayName("only re-logs when a field changes by more than the configured percentage")
        @CsvSource({
                // baseline, new, threshold, expectedShouldLog
                "'100,100,100,100', '105,100,100,100', 10, false",
                "'100,100,100,100', '100,100,150,100', 10, true",
                "'0,0,0,0',         '0,0,0,0',         10, false",
                "'0,0,0,0',         '50,0,0,0',        10, true"
        })
        void changeDetection(String baseline, String updated, int threshold, boolean expectedShouldLog) {
            CommandState cs = new CommandState("cmd");
            cs.updateLastLoggedTimings(toTimings(baseline));

            assertEquals(expectedShouldLog, cs.shouldLogTimings(toTimings(updated), threshold));
        }

        private Timings toTimings(String csv) {
            long[] v = java.util.Arrays.stream(csv.split(",")).mapToLong(Long::parseLong).toArray();
            return new Timings(v[0], v[1], v[2], v[3]);
        }
    }

    @Nested
    @DisplayName("lifecycle flags")
    class LifecycleFlags {

        @Test
        @DisplayName("markKillAll() sets shouldKillAll() permanently")
        void markKillAll_setsFlag() {
            CommandState cs = new CommandState("cmd");
            assertFalse(cs.shouldKillAll());
            cs.markKillAll();
            assertTrue(cs.shouldKillAll());
        }

        @Test
        @DisplayName("terminate() also marks allJobsEnded()")
        void terminate_marksAllJobsEnded() {
            CommandState cs = new CommandState("cmd");
            assertFalse(cs.allJobsEnded());
            cs.terminate();
            assertTrue(cs.allJobsEnded());
        }
    }
}