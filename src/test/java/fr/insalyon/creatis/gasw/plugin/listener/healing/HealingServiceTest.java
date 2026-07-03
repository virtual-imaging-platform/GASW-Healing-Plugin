package fr.insalyon.creatis.gasw.plugin.listener.healing;

import fr.insalyon.creatis.gasw.GaswConfiguration;
import fr.insalyon.creatis.gasw.GaswNotification;
import fr.insalyon.creatis.gasw.bean.Job;
import fr.insalyon.creatis.gasw.dao.DAOException;
import fr.insalyon.creatis.gasw.dao.JobDAO;
import fr.insalyon.creatis.gasw.dao.JobMinorStatusDAO;
import fr.insalyon.creatis.gasw.plugin.listener.healing.execution.CommandState;
import fr.insalyon.creatis.gasw.plugin.listener.healing.execution.HealingService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class HealingServiceTest {

    @Mock
    private GaswConfiguration gaswConfiguration;

    @Mock
    private HealingConfiguration healingConfiguration;

    @Mock
    private GaswNotification gaswNotification;

    @Mock
    private JobDAO jobDAO;

    @Mock
    private JobMinorStatusDAO jobMinorStatusDAO;

    private HealingService service;

    @BeforeEach
    public void setUp() {
        service = new HealingService(gaswConfiguration, healingConfiguration, gaswNotification, jobDAO, jobMinorStatusDAO);
    }

    @Nested
    @DisplayName("computeAndUpdateErrorRates()")
    class ComputeAndUpdateErrorRates {

        @ParameterizedTest(name =  "minInvocations={0}, jobErrorThreshold={1}%, invocationErrorThreshold={2}% -> killAll={3}")
        @DisplayName("evaluate kill-all marking when the job error rate or the invocation error rate breaches its threshold or not")
        @CsvSource({
                "1, 100, 101, false", // below minInvocations
                "2, 50, 101, true", // above jobError threshold
                "2, 100, 75, true", // above invocationError threshold
                "2, 100, 101, false" // below thresholds
        })
        void errorRateBreach_triggers(int minInvocations, double jobThreshold, double invocationThreshold, boolean expected) throws DAOException {
            CommandState state = new CommandState("cmd");
            when(jobDAO.getJobsByCommand("cmd")).thenReturn(List.of(mock(Job.class), mock(Job.class), mock(Job.class), mock(Job.class)));
            when(jobDAO.getFailedByCommand("cmd")).thenReturn(List.of(mock(Job.class), mock(Job.class), mock(Job.class)));
            when(jobDAO.getInvocationsByCommand("cmd")).thenReturn(List.of(1, 2, 3));
            when(jobDAO.getFailedJobsByInvocationID(anyInt())).thenReturn(List.of(mock(Job.class), mock(Job.class)));
            when(healingConfiguration.getMinInvocations()).thenReturn(minInvocations);
            when(healingConfiguration.getMaxErrorJobPercentage()).thenReturn(jobThreshold);
            lenient().when(healingConfiguration.getMaxErrorInvocationPercentage()).thenReturn(invocationThreshold);

            service.computeAndUpdateErrorRates(state);

            assertEquals(expected, state.shouldKillAll());
        }

        @Test
        @DisplayName("does not re-evaluate or un-mark kill-all once already triggered")
        void alreadyTriggered_isIdempotent() throws DAOException {
            CommandState state = new CommandState("cmd");
            state.markKillAll();

            service.computeAndUpdateErrorRates(state);

            assertTrue(state.shouldKillAll());
        }

        @Test
        @DisplayName("updates rates on state even when no threshold is breached")
        void updatesRatesRegardlessOfBreach() throws DAOException {
            CommandState state = new CommandState("cmd");
            when(jobDAO.getJobsByCommand("cmd")).thenReturn(List.of(mock(Job.class), mock(Job.class)));
            when(jobDAO.getFailedByCommand("cmd")).thenReturn(List.of(mock(Job.class)));
            when(jobDAO.getInvocationsByCommand("cmd")).thenReturn(Collections.emptyList());

            service.computeAndUpdateErrorRates(state);

            assertEquals(50.0, state.getJobErrorRate(), 0.001);
            assertEquals(0.0, state.getInvocationPartialErrorRate(), 0.001);
        }

        @Test
        @DisplayName("does not divide by zero when there are no jobs at all")
        void zeroTotalJobs_doesNotDivideByZero() throws DAOException {
            CommandState state = new CommandState("cmd");
            when(jobDAO.getJobsByCommand("cmd")).thenReturn(Collections.emptyList());
            when(jobDAO.getFailedByCommand("cmd")).thenReturn(Collections.emptyList());
            when(jobDAO.getInvocationsByCommand("cmd")).thenReturn(Collections.emptyList());

            service.computeAndUpdateErrorRates(state);
            // Should safely default to 0.0 when no jobs exist
            assertEquals(0.0, state.getJobErrorRate(), 0.001);
        }
    }

    @Nested
    @DisplayName("killAllJobs()")
    class KillAllJobs {

        @Test
        @DisplayName("marks allJobsEnded when no active jobs remain")
        void marksEnded_whenNoActiveJobsRemain() throws DAOException {
            CommandState state = new CommandState("cmd");
            when(jobDAO.getInvocationsByCommand("cmd")).thenReturn(Collections.emptyList());
            when(jobDAO.getActiveJobsByCommand("cmd")).thenReturn(Collections.emptyList());

            service.killAllJobs(state);

            assertTrue(state.allJobsEnded());
        }

        @Test
        @DisplayName("does not mark ended while active jobs remain")
        void doesNotMarkEnded_whenActiveJobsRemain() throws DAOException {
            CommandState state = new CommandState("cmd");
            when(jobDAO.getInvocationsByCommand("cmd")).thenReturn(Collections.emptyList());
            when(jobDAO.getActiveJobsByCommand("cmd")).thenReturn(List.of(mock(Job.class)));

            service.killAllJobs(state);

            assertFalse(state.allJobsEnded());
        }
    }
}