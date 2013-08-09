package org.threadly.concurrent;

import static org.junit.Assert.*;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.SimpleSchedulerInterfaceTest.SimpleSchedulerFactory;
import org.threadly.concurrent.lock.NativeLockFactory;
import org.threadly.concurrent.lock.StripedLock;
import org.threadly.concurrent.lock.VirtualLock;
import org.threadly.test.concurrent.TestCondition;
import org.threadly.test.concurrent.TestRunnable;

@SuppressWarnings("javadoc")
public class TaskSchedulerDistributorTest {
  private static final int PARALLEL_LEVEL = 2;
  private static final int RUNNABLE_COUNT_PER_LEVEL = 5;
  
  private volatile boolean ready;
  private PriorityScheduledExecutor scheduler;
  private VirtualLock agentLock;
  private TaskSchedulerDistributor distributor;
  
  @Before
  public void setup() {
    scheduler = new PriorityScheduledExecutor(PARALLEL_LEVEL + 1, 
                                              PARALLEL_LEVEL * 2, 
                                              1000 * 10, 
                                              TaskPriority.High, 
                                              PriorityScheduledExecutor.DEFAULT_LOW_PRIORITY_MAX_WAIT_IN_MS);
    StripedLock sLock = new StripedLock(1, new NativeLockFactory()); // TODO - test with testable lock
    agentLock = sLock.getLock(null);  // there should be only one lock
    distributor = new TaskSchedulerDistributor(scheduler, sLock);
    ready = false;
  }
  
  @After
  public void tearDown() {
    scheduler.shutdown();
    scheduler = null;
    agentLock = null;
    distributor = null;
    ready = false;
  }
  
  private List<TDRunnable> populate(final Object testLock, 
                                    final AddHandler ah) {
    final List<TDRunnable> runs = new ArrayList<TDRunnable>(PARALLEL_LEVEL * RUNNABLE_COUNT_PER_LEVEL);
    
    scheduler.execute(new Runnable() {
      @Override
      public void run() {
        // hold agent lock to prevent execution till ready
        synchronized (agentLock) {
          synchronized (testLock) {
            for (int i = 0; i < PARALLEL_LEVEL; i++) {
              ThreadContainer tc = new ThreadContainer();
              TDRunnable previous = null;
              for (int j = 0; j < RUNNABLE_COUNT_PER_LEVEL; j++) {
                TDRunnable tr = new TDRunnable(tc, previous);
                runs.add(tr);
                ah.addTDRunnable(tc, tr);
                
                previous = tr;
              }
            }
            
            ready = true;
          }
        }
      }
    });
    
    // block till ready to ensure other thread got lock
    new TestCondition() {
      @Override
      public boolean get() {
        return ready;
      }
    }.blockTillTrue();
    
    return runs;
  }
  
  @Test (expected = IllegalArgumentException.class)
  public void constructorFail() {
    new TaskSchedulerDistributor(1, null);
    
    fail("Exception should have been thrown");
  }
  
  @Test (expected = IllegalArgumentException.class)
  public void getSimpleSchedulerForKeyFail() {
    distributor.getSimpleSchedulerForKey(null);
  }
  
  @Test
  public void testGetExecutor() {
    assertTrue(scheduler == distributor.getExecutor());
  }
  
  @Test
  public void testExecutes() {
    final Object testLock = new Object();
    
    List<TDRunnable> runs = populate(testLock, 
                                     new AddHandler() {
      @Override
      public void addTDRunnable(Object key, TDRunnable tdr) {
        distributor.addTask(key, tdr);
      }
    });
    
    synchronized (testLock) {
      Iterator<TDRunnable> it = runs.iterator();
      while (it.hasNext()) {
        TDRunnable tr = it.next();
        tr.blockTillFinished(1000);
        assertEquals(tr.getRunCount(), 1); // verify each only ran once
        assertTrue(tr.threadTracker.threadConsistent);  // verify that all threads for a given key ran in the same thread
        assertTrue(tr.previousRanFirst);  // verify runnables were run in order
      }
    }
  }
  
  @Test
  public void testExecuteFail() {
    try {
      distributor.addTask(null, new TestRunnable());
      fail("Exception should have been thrown");
    } catch (IllegalArgumentException e) {
      // expected
    }

    try {
      distributor.addTask(new Object(), null);
      fail("Exception should have been thrown");
    } catch (IllegalArgumentException e) {
      // expected
    }
  }
  
  @Test
  public void scheduleExecutionTest() {
    final int scheduleDelay = 50;

    final Object testLock = new Object();
    
    List<TDRunnable> runs = populate(testLock, 
                                     new AddHandler() {
      @Override
      public void addTDRunnable(Object key, TDRunnable tdr) {
        distributor.schedule(key, tdr, scheduleDelay);
      }
    });
    
    synchronized (testLock) {
      Iterator<TDRunnable> it = runs.iterator();
      while (it.hasNext()) {
        TDRunnable tr = it.next();
        tr.blockTillFinished(1000);
        assertEquals(tr.getRunCount(), 1); // verify each only ran once
        assertTrue(tr.getDelayTillFirstRun() >= scheduleDelay);
        assertTrue(tr.threadTracker.runningConsistent);  // verify that it never run in parallel
      }
    }
  }
  
  @Test
  public void scheduleExecutionFail() {
    try {
      distributor.schedule(new Object(), null, 1000);
      fail("Exception should have been thrown");
    } catch (IllegalArgumentException e) {
      // expected
    }
    try {
      distributor.schedule(new Object(), new TestRunnable(), -1);
      fail("Exception should have been thrown");
    } catch (IllegalArgumentException e) {
      // expected
    }
    try {
      distributor.schedule(null, new TestRunnable(), 100);
      fail("Exception should have been thrown");
    } catch (IllegalArgumentException e) {
      // expected
    }
  }
  
  @Test
  public void recurringExecutionTest() {
    final int recurringDelay = 50;

    final Object testLock = new Object();
    
    List<TDRunnable> runs = populate(testLock, 
                                     new AddHandler() {
      int initialDelay = 0;
      @Override
      public void addTDRunnable(Object key, TDRunnable tdr) {
        distributor.scheduleWithFixedDelay(key, tdr, initialDelay++, 
                                           recurringDelay);
      }
    });
    
    synchronized (testLock) {
      Iterator<TDRunnable> it = runs.iterator();
      while (it.hasNext()) {
        TDRunnable tr = it.next();
        assertTrue(tr.getDelayTillRun(2) >= recurringDelay);
        tr.blockTillFinished(10 * 1000, 3);
        assertTrue(tr.threadTracker.runningConsistent);  // verify that it never run in parallel
      }
    }
  }
  
  @Test
  public void recurringExecutionFail() {
    try {
      distributor.scheduleWithFixedDelay(new Object(), null, 1000, 100);
      fail("Exception should have been thrown");
    } catch (IllegalArgumentException e) {
      // expected
    }
    try {
      distributor.scheduleWithFixedDelay(new Object(), new TestRunnable(), -1, 100);
      fail("Exception should have been thrown");
    } catch (IllegalArgumentException e) {
      // expected
    }
    try {
      distributor.scheduleWithFixedDelay(new Object(), new TestRunnable(), 100, -1);
      fail("Exception should have been thrown");
    } catch (IllegalArgumentException e) {
      // expected
    }
    try {
      distributor.scheduleWithFixedDelay(null, new TestRunnable(), 100, 100);
      fail("Exception should have been thrown");
    } catch (IllegalArgumentException e) {
      // expected
    }
  }
  
  @Test
  public void keyBasedSchedulerExecuteTest() {
    KeyBasedSimpleSchedulerFactory factory = new KeyBasedSimpleSchedulerFactory();
    
    SimpleSchedulerInterfaceTest.executeTest(factory);
  }
  
  @Test (expected = IllegalArgumentException.class)
  public void keyBasedSchedulerExecuteFail() {
    KeyBasedSimpleSchedulerFactory factory = new KeyBasedSimpleSchedulerFactory();
    
    SimpleSchedulerInterfaceTest.executeFail(factory);
  }
  
  @Test
  public void keyBasedSchedulerScheduleTest() {
    KeyBasedSimpleSchedulerFactory factory = new KeyBasedSimpleSchedulerFactory();
    
    SimpleSchedulerInterfaceTest.scheduleTest(factory);
  }
  
  @Test
  public void keyBasedSchedulerScheduleFail() {
    KeyBasedSimpleSchedulerFactory factory = new KeyBasedSimpleSchedulerFactory();
    
    SimpleSchedulerInterfaceTest.scheduleFail(factory);
  }
  
  @Test
  public void keyBasedSchedulerRecurringTest() {
    KeyBasedSimpleSchedulerFactory factory = new KeyBasedSimpleSchedulerFactory();
    
    SimpleSchedulerInterfaceTest.recurringExecutionTest(factory);
  }
  
  @Test
  public void keyBasedSchedulerRecurringFail() {
    KeyBasedSimpleSchedulerFactory factory = new KeyBasedSimpleSchedulerFactory();
    
    SimpleSchedulerInterfaceTest.recurringExecutionFail(factory);
  }
  
  @Test
  public void keyBasedSchedulerIsShutdownTest() {
    // setup
    scheduler.shutdown();
    assertTrue(scheduler.isShutdown());
    
    //verify
    assertTrue(distributor.getSimpleSchedulerForKey("foo").isShutdown());
  }
  
  private interface AddHandler {
    public void addTDRunnable(Object key, TDRunnable tdr);
  }
  
  private class TDRunnable extends TestRunnable {
    private final TDRunnable previousRunnable;
    private final ThreadContainer threadTracker;
    private volatile boolean previousRanFirst;
    
    private TDRunnable(ThreadContainer threadTracker, 
                       TDRunnable previousRunnable) {
      this.threadTracker = threadTracker;
      this.previousRunnable = previousRunnable;
      previousRanFirst = false;
    }
    
    @Override
    public void handleRunStart() {
      threadTracker.running();
      
      if (previousRunnable != null) {
        previousRanFirst = previousRunnable.ranOnce();
      } else {
        previousRanFirst = true;
      }
      threadTracker.done();
    }
  }
  
  private class ThreadContainer {
    private Thread runningThread = null;
    private boolean threadConsistent = true;
    private boolean running = false;
    private boolean runningConsistent = true;
    
    public synchronized void running() {
      if (running) {
        runningConsistent = false;
      }
      running = true;
      if (runningThread != null) {
        threadConsistent = threadConsistent && runningThread.equals(Thread.currentThread());
      }
      runningThread = Thread.currentThread();
    }

    public synchronized void done() {
      if (! running) {
        runningConsistent = false;
      }
      running = false;
    }
    
    @Override
    public String toString() {
      return Integer.toHexString(System.identityHashCode(this));
    }
  }

  private class KeyBasedSimpleSchedulerFactory implements SimpleSchedulerFactory {
    private final List<PriorityScheduledExecutor> executors;
    
    private KeyBasedSimpleSchedulerFactory() {
      Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          // ignored
        }
      });
      
      executors = new LinkedList<PriorityScheduledExecutor>();
    }
    
    @Override
    public SimpleSchedulerInterface make(int poolSize, boolean prestartIfAvailable) {
      PriorityScheduledExecutor scheduler = new PriorityScheduledExecutor(poolSize, 
                                                                          poolSize, 
                                                                          1000 * 10);
      executors.add(scheduler);
      if (prestartIfAvailable) {
        scheduler.prestartAllCoreThreads();
      }
      
      TaskSchedulerDistributor distributor = new TaskSchedulerDistributor(poolSize, scheduler);
      
      return distributor.getSimpleSchedulerForKey(this);
    }
    
    @Override
    public void shutdown() {
      Iterator<PriorityScheduledExecutor> it = executors.iterator();
      while (it.hasNext()) {
        it.next().shutdown();
        it.remove();
      }
    }
  }
}
