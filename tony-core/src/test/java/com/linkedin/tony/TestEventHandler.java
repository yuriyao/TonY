/**
 * Copyright 2018 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.tony;

import com.linkedin.tony.events.ApplicationInited;
import com.linkedin.tony.events.Event;
import com.linkedin.tony.events.EventHandler;
import com.linkedin.tony.events.EventType;
import com.linkedin.tony.util.Utils;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.avro.file.DataFileWriter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static com.linkedin.tony.util.ParserUtils.*;
import static org.mockito.Mockito.*;
import static org.testng.Assert.*;


public class TestEventHandler {
  private static final Log LOG = LogFactory.getLog(TestEventHandler.class);
  private FileSystem fs = null;
  private BlockingQueue<Event> eventQueue;
  private EventHandler eventHandlerThread;
  private Event eEventWrapper;
  private ApplicationInited eAppInitEvent = new ApplicationInited("app123", 1, "fakehost");
  private Path jobDir = new Path("./src/test/resources/jobDir");
  private TonyJobMetadata metadata = new TonyJobMetadata();

  @BeforeClass
  public void setup() {
    HdfsConfiguration conf = new HdfsConfiguration();
    try {
      fs = FileSystem.get(conf);
    } catch (Exception e) {
      fail("Failed setting up FileSystem object");
    }
    eEventWrapper = new Event();
    eEventWrapper.setType(EventType.APPLICATION_INITED);
    eEventWrapper.setEvent(eAppInitEvent);
    eventQueue = new LinkedBlockingQueue<>();
  }

  @Test
  public void testEventHandlerConstructor_failedToSetUpWriter() throws IOException {
    FileSystem mockFs = mock(FileSystem.class);
    when(mockFs.create(any(Path.class))).thenThrow(new IOException("IO Exception"));

    // we don't need to assign to a variable since we don't use it
    new EventHandler(mockFs, eventQueue);

    verify(mockFs).create(any(Path.class));
  }

  @Test
  public void testEventHandlerE2E_success() throws InterruptedException, IOException {
    fs.mkdirs(jobDir);
    eventHandlerThread = new EventHandler(fs, eventQueue);
    eventHandlerThread.start();
    eventHandlerThread.emitEvent(eEventWrapper);
    eventHandlerThread.stop(jobDir, metadata);
    eventHandlerThread.join();
    List<Event> events = parseEvents(fs, jobDir);
    Event aEventWrapper = events.get(0);
    ApplicationInited aAppInitEvent = (ApplicationInited) aEventWrapper.getEvent();

    assertEquals(events.size(), 1);
    assertEquals(aAppInitEvent.getApplicationId(), eAppInitEvent.getApplicationId());
    assertEquals(aAppInitEvent.getNumTasks(), eAppInitEvent.getNumTasks());
    assertEquals(aAppInitEvent.getHost(), eAppInitEvent.getHost());
    assertEquals(aEventWrapper.getType(), eEventWrapper.getType());
    assertEquals(aEventWrapper.getTimestamp(), eEventWrapper.getTimestamp());
    assertEquals(fs.listStatus(jobDir).length, 1);

    Utils.cleanupHDFSPath(fs.getConf(), jobDir);
  }

  @Test
  public void testEventHandlerE2E_failedJobDirNotSet() throws InterruptedException, IOException {
    fs.mkdirs(jobDir);
    eventHandlerThread = new EventHandler(fs, eventQueue);
    eventHandlerThread.start();
    eventHandlerThread.stop(null, metadata); // jobDir == null
    eventHandlerThread.join();

    assertEquals(fs.listStatus(jobDir).length, 0);

    Utils.cleanupHDFSPath(fs.getConf(), jobDir);
  }

  @Test
  public void testWriteEvent() throws IOException {
    DataFileWriter<Event> writer = mock(DataFileWriter.class);
    eventQueue.add(eEventWrapper);
    eventHandlerThread = new EventHandler(fs, eventQueue);

    assertEquals(eventQueue.size(), 1);
    eventHandlerThread.writeEvent(eventQueue, writer); // should remove the event from queue
    assertEquals(eventQueue.size(), 0);
    verify(writer).append(eEventWrapper);
  }

  @Test
  public void testDrainQueue() {
    DataFileWriter<Event> writer = mock(DataFileWriter.class);
    eventQueue.add(eEventWrapper);
    eventQueue.add(eEventWrapper);
    eventQueue.add(eEventWrapper);
    eventQueue.add(eEventWrapper);
    eventHandlerThread = new EventHandler(fs, eventQueue);

    assertEquals(eventQueue.size(), 4);
    eventHandlerThread.drainQueue(eventQueue, writer); // should drain the queue
    assertEquals(eventQueue.size(), 0);
  }

  @AfterClass
  public void cleanUp() throws IOException {
    fs.delete(new Path(Constants.TMP_AVRO), true);
  }
}
