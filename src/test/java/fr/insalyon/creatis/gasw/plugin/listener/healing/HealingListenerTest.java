package fr.insalyon.creatis.gasw.plugin.listener.healing;

import fr.insalyon.creatis.gasw.GaswExitCode;
import fr.insalyon.creatis.gasw.GaswOutput;
import fr.insalyon.creatis.gasw.bean.Job;
import fr.insalyon.creatis.gasw.dao.JobMinorStatusDAO;
import fr.insalyon.creatis.gasw.plugin.listener.healing.execution.CommandState;
import fr.insalyon.creatis.gasw.plugin.listener.healing.execution.CommandStateRegistry;
import fr.insalyon.creatis.gasw.plugin.listener.healing.execution.HealingService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class HealingListenerTest {

    @Mock
    private JobMinorStatusDAO jobMinorStatusDAO;

    @Mock
    private HealingService healingService;

    @Mock
    private CommandStateRegistry commandStateRegistry;

    @Mock
    private CommandState commandState;

    private HealingListener healingListener;

    @BeforeEach
    public void setUp() {
        healingListener = new HealingListener(jobMinorStatusDAO, healingService, commandStateRegistry);
    }

    @Test
    @DisplayName("jobSubmitted registers the command in the state registry")
    void jobSubmitted_registersCommand() {
        Job job = new Job();
        job.setCommand("test-command");
        when(commandStateRegistry.getOrCreate("test-command")).thenReturn(commandState);

        healingListener.jobSubmitted(job);

        verify(commandStateRegistry).getOrCreate("test-command");
    }

    @Nested
    @DisplayName("jobFinished()")
    class JobFinished {

        @ParameterizedTest(name = "{0} triggers error-rate computation")
        @DisplayName("computes error rates for any non-success, non-cancelled exit code")
        @EnumSource(value = GaswExitCode.class, names = {"SUCCESS", "EXECUTION_CANCELED"}, mode = EnumSource.Mode.EXCLUDE)
        void failureCodes_triggerErrorRateComputation(GaswExitCode exitCode) throws Exception {
            GaswOutput output = output("test-job-fail", exitCode);
            when(commandStateRegistry.getOrCreate("test-job-fail")).thenReturn(commandState);

            healingListener.jobFinished(output);

            verify(commandStateRegistry).getOrCreate("test-job-fail");
            verify(healingService).computeAndUpdateErrorRates(commandState);
        }

        @ParameterizedTest(name = "{0} does not trigger error-rate computation")
        @DisplayName("skips error-rate computation on success or cancellation")
        @EnumSource(value = GaswExitCode.class, names = {"SUCCESS", "EXECUTION_CANCELED"})
        void successOrCanceled_skipsErrorRateComputation(GaswExitCode exitCode) throws Exception {
            GaswOutput output = output("test-job-success", exitCode);
            when(commandStateRegistry.getOrCreate("test-job-success")).thenReturn(commandState);

            healingListener.jobFinished(output);

            verify(commandStateRegistry).getOrCreate("test-job-success");
            verify(healingService, never()).computeAndUpdateErrorRates(any());
        }

        private GaswOutput output(String jobId, GaswExitCode exitCode) {
            return new GaswOutput(jobId, exitCode, "", Map.of(), null, null, null, null);
        }
    }

    @Nested
    @DisplayName("job ID -> command parsing (JOB_ID_PATTERN)")
    class JobIdParsing {

        @Test
        @DisplayName("strips the numeric Moteur job ID suffix to recover the command name")
        void stripsNumericSuffix() {
            assertEquals("my-command", HealingListener.JOB_ID_PATTERN.matcher("my-command-4072786226984043").replaceAll(""));
        }

        @Test
        @DisplayName("strips the numeric suffix and trailing .jdl extension")
        void stripsNumericSuffixWithJdlExtension() {
            assertEquals("my-command",  HealingListener.JOB_ID_PATTERN.matcher("my-command-4072786226984043.jdl").replaceAll(""));
        }

        @Test
        @DisplayName("leaves commands without a trailing numeric ID unchanged")
        void leavesPlainCommandUnchanged() {
            assertEquals("my-command",  HealingListener.JOB_ID_PATTERN.matcher("my-command").replaceAll(""));
        }
    }

    @Test
    @DisplayName("terminate() delegates to terminateAll() on the registry")
    void terminate_delegatesToRegistry() {
        healingListener.terminate();

        verify(commandStateRegistry).terminateAll();
    }
}
