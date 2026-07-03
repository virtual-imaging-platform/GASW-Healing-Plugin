/* Copyright CNRS-CREATIS
 *
 * Rafael Ferreira da Silva
 * rafael.silva@creatis.insa-lyon.fr
 * http://www.rafaelsilva.com
 *
 * This software is governed by the CeCILL  license under French law and
 * abiding by the rules of distribution of free software.  You can  use,
 * modify and/ or redistribute the software under the terms of the CeCILL
 * license as circulated by CEA, CNRS and INRIA at the following URL
 * "http://www.cecill.info".
 *
 * As a counterpart to the access to the source code and  rights to copy,
 * modify and redistribute granted by the license, users are provided only
 * with a limited warranty  and the software's author,  the holder of the
 * economic rights,  and the successive licensors  have only  limited
 * liability.
 *
 * In this respect, the user's attention is drawn to the risks associated
 * with loading,  using,  modifying and/or developing or reproducing the
 * software by the user in light of its specific status of free software,
 * that may mean  that it is complicated to manipulate,  and  that  also
 * therefore means  that it is reserved for developers  and  experienced
 * professionals having in-depth computer knowledge. Users are therefore
 * encouraged to load and test the software's suitability as regards their
 * requirements in conditions enabling the security of their systems and/or
 * data to be ensured and,  more generally, to use and operate it in the
 * same conditions as regards security.
 *
 * The fact that you are presently reading this means that you have had
 * knowledge of the CeCILL license and that you accept its terms.
 */

package fr.insalyon.creatis.gasw.plugin.listener.healing.execution;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class CommandState {

    private final String command;
    private final Queue<Long> setupTimes;
    private final Queue<Long> inputTimes;
    private final Queue<Long> executionTimes;
    private final Queue<Long> outputTimes;
    private final AtomicReference<Double> jobErrorRate;
    private final AtomicReference<Double> invocationPartialErrorRate;
    private final ConcurrentHashMap<String, Long> lastLoggedTimes;
    private final AtomicBoolean killAllJobs;
    private final AtomicBoolean allJobsEnded;


    public CommandState(String command) {
        this.command = command;
        setupTimes = new ConcurrentLinkedQueue<>();
        inputTimes = new ConcurrentLinkedQueue<>();
        executionTimes = new ConcurrentLinkedQueue<>();
        outputTimes = new ConcurrentLinkedQueue<>();
        jobErrorRate = new AtomicReference<>(0.0);
        invocationPartialErrorRate = new AtomicReference<>(0.0);
        lastLoggedTimes = new ConcurrentHashMap<>();
        killAllJobs = new AtomicBoolean(false);
        allJobsEnded = new AtomicBoolean(false);
    }

    public void addSetupTime(long t) {
        setupTimes.add(t);
    }

    public void addDownloadTime(long t) {
        inputTimes.add(t);
    }

    public void addExecutionTime(long t) {
        executionTimes.add(t);
    }

    public void addUploadTime(long t) {
        outputTimes.add(t);
    }

    public void updateErrorRates(double jobRate, double invocationRate) {
        this.jobErrorRate.set(jobRate);
        this.invocationPartialErrorRate.set(invocationRate);
    }

    public boolean hasEnoughData() {
        return outputTimes.size() > 1;
    }

    public String getCommand() {
        return command;
    }

    public double getJobErrorRate() {
        return jobErrorRate.get();
    }

    public double getInvocationPartialErrorRate() {
        return invocationPartialErrorRate.get();
    }

    public Timings computeMedians() {
        return new Timings(
                getMedianValue(setupTimes),
                getMedianValue(inputTimes),
                getMedianValue(executionTimes),
                getMedianValue(outputTimes)
        );
    }

    public boolean shouldLogTimings(Timings current, int changePercentage) {
        if (lastLoggedTimes.isEmpty()) return true;
        return isChangeGreaterThanPercentage(lastLoggedTimes.get("setup"), current.setup(), changePercentage)
                || isChangeGreaterThanPercentage(lastLoggedTimes.get("input"), current.input(), changePercentage)
                || isChangeGreaterThanPercentage(lastLoggedTimes.get("execution"), current.execution(), changePercentage)
                || isChangeGreaterThanPercentage(lastLoggedTimes.get("output"), current.output(), changePercentage);
    }

    public void updateLastLoggedTimings(Timings t) {
        lastLoggedTimes.put("setup", t.setup());
        lastLoggedTimes.put("input", t.input());
        lastLoggedTimes.put("execution", t.execution());
        lastLoggedTimes.put("output", t.output());
    }

    private long getMedianValue(Queue<Long> queue) {
        if (queue.isEmpty()) return 0L;
        List<Long> list = new ArrayList<>(queue);
        Collections.sort(list);
        int size = list.size();
        return size % 2 == 1
                ? list.get(size / 2)
                : (list.get(size / 2 - 1) + list.get(size / 2)) / 2;
    }

    private boolean isChangeGreaterThanPercentage(long v1, long v2, int percentage) {
        if (v1 == 0 && v2 == 0) return false;
        double ratio = 1 - percentage / 100.;
        double max = Math.max(v1, v2);
        double min = Math.min(v1, v2);
        return (min / max) < ratio;
    }

    public record Timings(long setup, long input, long execution, long output) {
        public long total() { return setup + input + execution + output; }
    }

    public void markKillAll() {
        this.killAllJobs.set(true);
    }

    public boolean shouldKillAll() {
        return killAllJobs.get();
    }

    public void markAllJobsEnded() {
        this.allJobsEnded.set(true);
    }

    public boolean allJobsEnded() {
        return allJobsEnded.get();
    }

    public void terminate() {
        this.allJobsEnded.set(true);
    }


}