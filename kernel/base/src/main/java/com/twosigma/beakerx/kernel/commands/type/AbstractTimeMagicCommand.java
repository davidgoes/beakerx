/*
 *  Copyright 2017 TWO SIGMA OPEN SOURCE, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.twosigma.beakerx.kernel.commands.type;

import com.twosigma.beakerx.jvm.object.SimpleEvaluationObject.EvaluationStatus;
import com.twosigma.beakerx.kernel.KernelFunctionality;
import com.twosigma.beakerx.kernel.commands.item.CommandItemWithResult;
import com.twosigma.beakerx.kernel.msg.MessageCreator;
import com.twosigma.beakerx.message.Message;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.text.StrTokenizer;

public abstract class AbstractTimeMagicCommand extends MagicCommand {

  protected KernelFunctionality kernel;

  public AbstractTimeMagicCommand(String name, String parameters, Set<MagicCommandType> magicCommandTypes, MessageCreator messageCreator, KernelFunctionality kernel) {
    super(name, parameters, magicCommandTypes, messageCreator);
    this.kernel = kernel;
  }

  protected CommandItemWithResult time(String codeToExecute, Message message, int executionCount) {
    CompletableFuture<TimeMeasureData> compileTime = new CompletableFuture<>();

    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    long currentThreadId = Thread.currentThread().getId();

    Long startWallTime = System.nanoTime();
    Long startCpuTotalTime = threadMXBean.getCurrentThreadCpuTime();
    Long startUserTime = threadMXBean.getCurrentThreadUserTime();

    kernel.executeCode(codeToExecute, message, executionCount, seo -> {
      Long endWallTime = System.nanoTime();
      Long endCpuTotalTime = threadMXBean.getThreadCpuTime(currentThreadId);
      Long endUserTime = threadMXBean.getThreadUserTime(currentThreadId);

      compileTime.complete(new TimeMeasureData(endCpuTotalTime - startCpuTotalTime,
          endUserTime - startUserTime,
          endWallTime - startWallTime));
    });
    String messageInfo = "CPU times: user %s, sys: %s, total: %s \nWall Time: %s\n";

    try {
      TimeMeasureData timeMeasuredData = compileTime.get();

      return new CommandItemWithResult(
          getMessageCreator().buildOutputMessage(message, String.format(messageInfo,
              format(timeMeasuredData.getCpuUserTime()),
              format(timeMeasuredData.getCpuTotalTime() - timeMeasuredData.getCpuUserTime()),
              format(timeMeasuredData.getCpuTotalTime()),
              format(timeMeasuredData.getWallTime())), false),
          getMessageCreator().buildReplyWithoutStatus(message, executionCount));

    } catch (InterruptedException | ExecutionException e) {
      return createErrorMessage(message, "There occurs problem during measuring time for your statement.", executionCount);
    }

  }

  public CommandItemWithResult timeIt(TimeItOption timeItOption, String codeToExecute,
      Message message, int executionCount) {
    String output = "%s ± %s per loop (mean ± std. dev. of %d run, %d loop each)";

    if (timeItOption.getNumber() < 0) {
      return createErrorMessage(message, "Number of execution must be bigger then 0", executionCount);
    }
    int number = timeItOption.getNumber() == 0 ? getBestNumber(codeToExecute) : timeItOption.getNumber();

    if (timeItOption.getRepeat() == 0) {
      return createErrorMessage(message, "Repeat value must be bigger then 0", executionCount);
    }

    CompletableFuture<Boolean> isStatementsCorrect = new CompletableFuture<>();
    kernel.executeCodeWithTimeMeasurement(codeToExecute, message, executionCount,
        executeCodeCallbackWithTime -> {
          if (executeCodeCallbackWithTime.getStatus().equals(EvaluationStatus.ERROR)) {
            isStatementsCorrect.complete(false);
          } else {
            isStatementsCorrect.complete(true);
          }
        });
    try {

      if (!isStatementsCorrect.get()) {
        return createErrorMessage(message, "Please correct your statement", executionCount);
      }

      List<Long> allRuns = new ArrayList<>();
      List<Long> timings = new ArrayList<>();

      CompletableFuture<Boolean> isReady = new CompletableFuture<>();

      IntStream.range(0, timeItOption.getRepeat()).forEach(repeatIter -> {
        IntStream.range(0, number).forEach(numberIter -> {
          kernel.executeCodeWithTimeMeasurement(codeToExecute, message, executionCount,
              executeCodeCallbackWithTime -> {
                allRuns.add(executeCodeCallbackWithTime.getPeriodOfEvaluationInNanoseconds());
                if (repeatIter == timeItOption.getRepeat() - 1 && numberIter == number - 1) {
                  isReady.complete(true);
                }
              });
        });
      });

      if (isReady.get()) {
        allRuns.forEach(run -> timings.add(run / number));

        //calculating average
        long average = timings.stream()
            .reduce((aLong, aLong2) -> aLong + aLong2)
            .orElse(0L) / timings.size();
        double stdev = Math.pow(timings.stream().map(currentValue -> Math.pow(currentValue - average, 2))
            .reduce((aDouble, aDouble2) -> aDouble + aDouble2)
            .orElse(0.0) / timings.size(), 0.5);

        if (timeItOption.getQuietMode()) {
          output = "";
        } else {
          output = String.format(output, format(average), format((long) stdev), timeItOption.getRepeat(), number);
        }

        return new CommandItemWithResult(
            getMessageCreator().buildOutputMessage(message, output, false),
            getMessageCreator().buildReplyWithoutStatus(message, executionCount));
      }
    } catch (InterruptedException | ExecutionException e) {
      return createErrorMessage(message, "There occurs problem with " + e.getMessage(), executionCount);
    }

    return createErrorMessage(message, "There occurs problem with timeIt operations", executionCount);

  }

  private int getBestNumber(String codeToExecute) {
    for (int value=0; value < 10;) {
      Double numberOfExecution = Math.pow(10, value);
      CompletableFuture<Boolean> keepLooking = new CompletableFuture<>();

      Long startTime = System.nanoTime();
      IntStream.range(0, numberOfExecution.intValue()).forEach(indexOfExecution -> {
        kernel.executeCode(codeToExecute, new Message(), 0, seo -> {
          if (numberOfExecution.intValue() - 1 == indexOfExecution) {
            if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime) > 0.2) {
              keepLooking.complete(false);
            } else {
              keepLooking.complete(true);
            }
          }
        });
      });

      try {
        if (keepLooking.get()) {
          value++;
        } else {
          return numberOfExecution.intValue();
        }
      } catch (ExecutionException | InterruptedException e) {
        throw new IllegalStateException("Cannot find best number of execution.");
      }
    }

    throw new IllegalStateException("Cannot find best number of execution.");
  }

  private class TimeMeasureData {
    private Long cpuTotalTime;
    private Long cpuUserTime;
    private Long wallTime;

    public TimeMeasureData(Long cpuTotalTime, Long cpuUserTime, Long wallTime) {
      this.cpuTotalTime = cpuTotalTime;
      this.cpuUserTime = cpuUserTime;
      this.wallTime = wallTime;
    }

    public Long getCpuTotalTime() {
      return cpuTotalTime;
    }

    public Long getCpuUserTime() {
      return cpuUserTime;
    }

    public Long getWallTime() {
      return wallTime;
    }
  }

  protected class TimeItOption {
    Integer number = 0;
    Integer repeat = 3;
    Boolean quietMode = false;

    public void setNumber(Integer number) {
      this.number = number;
    }

    public void setRepeat(Integer repeat) {
      this.repeat = repeat;
    }

    public void setQuietMode(Boolean quietMode) {
      this.quietMode = quietMode;
    }

    public Integer getNumber() {
      return number;
    }

    public Integer getRepeat() {
      return repeat;
    }

    public Boolean getQuietMode() {
      return quietMode;
    }
  }

  private String format(Long nanoSeconds) {
    if (nanoSeconds < 1000) {
      //leave in ns
      return nanoSeconds + " ns";
    } else if (nanoSeconds >= 1000 && nanoSeconds < 1_000_000) {
      //convert to µs
      return TimeUnit.NANOSECONDS.toMicros(nanoSeconds) + " µs";
    } else if (nanoSeconds > 1_000_000 && nanoSeconds < 1_000_000_000) {
      //convert to ms
      return TimeUnit.NANOSECONDS.toMillis(nanoSeconds) + " ms";
    } else {
      //convert to s
      return TimeUnit.NANOSECONDS.toSeconds(nanoSeconds) + " s";
    }
  }

  private Options createForTimeIt() {
    Options options = new Options();
    options.addOption("n", true, "Execute the given statement <N> times in a loop");
    options.addOption("r", true, "Repeat the loop iteration <R> times and take the best result. Default: 3");
    options.addOption("q", false, "Quiet, do not print result.");

    return options;
  }

  protected TimeItOption buildTimeItOption(String code) {
    TimeItOption timeItOption = new TimeItOption();

    try {
      StrTokenizer tokenizer = new StrTokenizer(code);

      CommandLineParser parser = new PosixParser();
      CommandLine cmd = parser.parse(createForTimeIt(), tokenizer.getTokenArray());

      if (cmd.hasOption('n')) {
        timeItOption.setNumber(Integer.valueOf(cmd.getOptionValue('n')));
      }
      if (cmd.hasOption('r')) {
        timeItOption.setRepeat(Integer.valueOf(cmd.getOptionValue('r')));
      }
      if (cmd.hasOption('q')) {
        timeItOption.setQuietMode(true);
      }

    } catch (ParseException e) {
      throw new IllegalArgumentException(e.getMessage());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Expected value must be a number " + e.getMessage().toLowerCase());
    }

    return timeItOption;
  }
}
