/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.tests.indexer;

import com.google.inject.Inject;
import org.apache.commons.io.IOUtils;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.granularity.GranularityType;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.testing.IntegrationTestingConfig;
import org.apache.druid.testing.guice.DruidTestModuleFactory;
import org.apache.druid.testing.utils.ITRetryUtil;
import org.apache.druid.tests.TestNGGroup;
import org.joda.time.Interval;
import org.joda.time.chrono.ISOChronology;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Test(groups = {TestNGGroup.COMPACTION, TestNGGroup.QUICKSTART_COMPATIBLE})
@Guice(moduleFactory = DruidTestModuleFactory.class)
public class ITCompactionTaskTest extends AbstractIndexerTest
{
  private static final Logger LOG = new Logger(ITCompactionTaskTest.class);
  private static final String INDEX_TASK = "/indexer/wikipedia_index_task.json";
  private static final String INDEX_QUERIES_RESOURCE = "/indexer/wikipedia_index_queries.json";
  private static final String INDEX_DATASOURCE = "wikipedia_index_test";

  private static final String COMPACTION_TASK = "/indexer/wikipedia_compaction_task.json";
  private static final String COMPACTION_TASK_WITH_SEGMENT_GRANULARITY = "/indexer/wikipedia_compaction_task_with_segment_granularity.json";
  private static final String COMPACTION_TASK_WITH_GRANULARITY_SPEC = "/indexer/wikipedia_compaction_task_with_granularity_spec.json";

  private static final String INDEX_TASK_WITH_TIMESTAMP = "/indexer/wikipedia_with_timestamp_index_task.json";

  @Inject
  private IntegrationTestingConfig config;

  private String fullDatasourceName;

  @BeforeMethod
  public void setFullDatasourceName(Method method)
  {
    fullDatasourceName = INDEX_DATASOURCE + config.getExtraDatasourceNameSuffix() + "-" + method.getName();
  }

  @Test
  public void testCompaction() throws Exception
  {
    loadDataAndCompact(INDEX_TASK, INDEX_QUERIES_RESOURCE, COMPACTION_TASK, null);
  }

  @Test
  public void testCompactionWithSegmentGranularity() throws Exception
  {
    loadDataAndCompact(INDEX_TASK, INDEX_QUERIES_RESOURCE, COMPACTION_TASK_WITH_SEGMENT_GRANULARITY, GranularityType.MONTH);
  }

  @Test
  public void testCompactionWithGranularitySpec() throws Exception
  {
    loadDataAndCompact(INDEX_TASK, INDEX_QUERIES_RESOURCE, COMPACTION_TASK_WITH_GRANULARITY_SPEC, GranularityType.MONTH);
  }

  @Test
  public void testCompactionWithTimestampDimension() throws Exception
  {
    loadDataAndCompact(INDEX_TASK_WITH_TIMESTAMP, INDEX_QUERIES_RESOURCE, COMPACTION_TASK, null);
  }

  private void loadDataAndCompact(
      String indexTask,
      String queriesResource,
      String compactionResource,
      GranularityType newSegmentGranularity
  ) throws Exception
  {
    loadData(indexTask);

    // 4 segments across 2 days
    checkNumberOfSegments(4);

    List<String> expectedIntervalAfterCompaction = coordinator.getSegmentIntervals(fullDatasourceName);
    expectedIntervalAfterCompaction.sort(null);
    try (final Closeable ignored = unloader(fullDatasourceName)) {
      String queryResponseTemplate;
      try {
        InputStream is = AbstractITBatchIndexTest.class.getResourceAsStream(queriesResource);
        queryResponseTemplate = IOUtils.toString(is, StandardCharsets.UTF_8);
      }
      catch (IOException e) {
        throw new ISE(e, "could not read query file: %s", queriesResource);
      }

      queryResponseTemplate = StringUtils.replace(
          queryResponseTemplate,
          "%%DATASOURCE%%",
          fullDatasourceName
      );


      queryHelper.testQueriesFromString(queryResponseTemplate);
      compactData(compactionResource, newSegmentGranularity);

      // The original 4 segments should be compacted into 2 new segments
      checkNumberOfSegments(2);
      queryHelper.testQueriesFromString(queryResponseTemplate);


      if (newSegmentGranularity != null) {
        List<String> newIntervals = new ArrayList<>();
        for (String interval : expectedIntervalAfterCompaction) {
          for (Interval newinterval : newSegmentGranularity.getDefaultGranularity().getIterable(new Interval(interval, ISOChronology.getInstanceUTC()))) {
            newIntervals.add(newinterval.toString());
          }
        }
        expectedIntervalAfterCompaction = newIntervals;
      }
      checkCompactionIntervals(expectedIntervalAfterCompaction);
    }
  }
  private void loadData(String indexTask) throws Exception
  {
    String taskSpec = getResourceAsString(indexTask);
    taskSpec = StringUtils.replace(taskSpec, "%%DATASOURCE%%", fullDatasourceName);
    final String taskID = indexer.submitTask(taskSpec);
    LOG.info("TaskID for loading index task %s", taskID);
    indexer.waitUntilTaskCompletes(taskID);

    ITRetryUtil.retryUntilTrue(
        () -> coordinator.areSegmentsLoaded(fullDatasourceName),
        "Segment Load"
    );
  }

  private void compactData(String compactionResource, GranularityType newSegmentGranularity) throws Exception
  {
    String template = getResourceAsString(compactionResource);
    template = StringUtils.replace(template, "%%DATASOURCE%%", fullDatasourceName);
    if (newSegmentGranularity != null) {
      template = StringUtils.replace(
          template,
          "%%SEGMENTGRANULARITY%%",
          newSegmentGranularity.name()
      );
    }

    final String taskID = indexer.submitTask(template);
    LOG.info("TaskID for compaction task %s", taskID);
    indexer.waitUntilTaskCompletes(taskID);

    ITRetryUtil.retryUntilTrue(
        () -> coordinator.areSegmentsLoaded(fullDatasourceName),
        "Segment Compaction"
    );
  }

  private void checkNumberOfSegments(int numExpectedSegments)
  {
    ITRetryUtil.retryUntilTrue(
        () -> {
          int metadataSegmentCount = coordinator.getSegments(fullDatasourceName).size();
          LOG.info("Current metadata segment count: %d, expected: %d", metadataSegmentCount, numExpectedSegments);
          return metadataSegmentCount == numExpectedSegments;
        },
        "Segment count check"
    );
  }

  private void checkCompactionIntervals(List<String> expectedIntervals)
  {
    Set<String> expectedIntervalsSet = new HashSet<>(expectedIntervals);
    ITRetryUtil.retryUntilTrue(
        () -> {
          final Set<String> intervalsAfterCompaction = new HashSet<>(coordinator.getSegmentIntervals(fullDatasourceName));
          System.out.println("ACTUAL: " + intervalsAfterCompaction);
          System.out.println("EXPECTED: " + expectedIntervalsSet);
          return intervalsAfterCompaction.equals(expectedIntervalsSet);
        },
        "Compaction interval check"
    );
  }
}
