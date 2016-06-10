package com.linkedin.d2.balancer.simple;

import com.linkedin.common.callback.Callback;
import com.linkedin.common.callback.FutureCallback;
import com.linkedin.common.util.None;
import com.linkedin.d2.balancer.ServiceUnavailableException;
import com.linkedin.d2.balancer.clients.RewriteClient;
import com.linkedin.d2.balancer.properties.ClusterProperties;
import com.linkedin.d2.balancer.properties.PropertyKeys;
import com.linkedin.d2.balancer.properties.ServiceProperties;
import com.linkedin.d2.balancer.properties.UriProperties;
import com.linkedin.d2.balancer.strategies.LoadBalancerStrategy;
import com.linkedin.d2.balancer.strategies.LoadBalancerStrategyFactory;
import com.linkedin.d2.balancer.strategies.degrader.DegraderLoadBalancerStrategyConfig;
import com.linkedin.d2.balancer.strategies.degrader.DegraderLoadBalancerStrategyFactoryV3;
import com.linkedin.d2.balancer.util.URIRequest;
import com.linkedin.d2.balancer.util.hashing.Ring;
import com.linkedin.d2.discovery.event.PropertyEventThread.PropertyEventShutdownCallback;
import com.linkedin.d2.discovery.event.SynchronousExecutorService;
import com.linkedin.d2.discovery.stores.mock.MockStore;
import com.linkedin.r2.message.RequestContext;
import com.linkedin.r2.message.rest.RestRequest;
import com.linkedin.r2.message.rest.RestRequestBuilder;
import com.linkedin.r2.message.rest.RestResponse;
import com.linkedin.r2.message.rest.RestResponseBuilder;
import com.linkedin.r2.message.stream.StreamRequest;
import com.linkedin.r2.message.stream.StreamResponse;
import com.linkedin.r2.transport.common.TransportClientFactory;
import com.linkedin.r2.transport.common.bridge.client.TransportClient;
import com.linkedin.r2.transport.common.bridge.common.TransportCallback;
import com.linkedin.r2.transport.common.bridge.common.TransportResponseImpl;
import com.linkedin.util.clock.Clock;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;

import static org.testng.Assert.assertFalse;


/**
 * LoadBalancerSimulator simulates the transporting delays of different hosts for d2
 * degraderloadbalancer debugging, testing and verifications.
 *
 * The simulator requires 5 inputs:
 * . ServiceProperties, ClusterProperties and UriProperties: represent the d2 configurations.
 * . DelayGenerator: provides the delays for each given Uri
 * . QPSGenerator: provides the number of queries per interval
 *
 * To control the simulator:
 * . Asynchronous call: run(long duration) and runUntil(long untilTime)
 * . Synchronous call: runWait(long duration)
 * . stop()
 *
 * To check the status:
 * . getClientCounters(): returns the hits for each URI during last interval
 * . getPoints(): returns the hashring points for each URI
 *
 */

public class LoadBalancerSimulator
{
  private static final Logger _log = LoggerFactory.getLogger(LoadBalancerSimulator.class);

  private final MockStore<ServiceProperties> _serviceRegistry = new MockStore<>();
  private final MockStore<ClusterProperties> _clusterRegistry = new MockStore<>();
  private final MockStore<UriProperties> _uriRegistry = new MockStore<>();
  private final SimpleLoadBalancer _loadBalancer;

  private final DelayGenerator<URI> _delayGenerator;
  private final QPSGenerator _qpsGenerator;

  private final ClockedExecutor _clockedExecutor;
  private final ScheduledExecutorService _executorService;

  private final Map<URI, Integer> _clientCounters = new HashMap<>();

  // the delay in milliseconds to schedule the first request
  private final int INIT_SCHEDULE_DELAY = 10;
  // How often to reschedule next set of requests
  private final long SCHEDULE_INTERVAL = DegraderLoadBalancerStrategyConfig.DEFAULT_UPDATE_INTERVAL_MS;
  /**
   * Return the delay for each T object
   * @param <T>
   */
  interface DelayGenerator<T>
  {
    long nextDelay(T t);
  }

  /**
   * Return the number of queries for the next interval
   */
  interface QPSGenerator
  {
    int nextQPS();
  }

  LoadBalancerSimulator(ServiceProperties serviceProperties, ClusterProperties clusterProperties,
      UriProperties uriProperties, DelayGenerator<URI> delayGenerator,
      QPSGenerator qpsGenerator) throws ExecutionException, InterruptedException
  {
    _executorService = new SynchronousExecutorService();
    _clockedExecutor = new ClockedExecutor();

    // mock the properties to pass in simulation info
    Map<String, Object> transportProperty = new HashMap<>(serviceProperties.getTransportClientProperties());
    transportProperty.put("ClockedExecutor", _clockedExecutor);
    Map<String, Object> strategyProperty = new HashMap<>(serviceProperties.getLoadBalancerStrategyProperties());
    strategyProperty.put(PropertyKeys.CLOCK, _clockedExecutor);

    ServiceProperties updatedServiceProperties = new ServiceProperties(serviceProperties.getServiceName(),
        serviceProperties.getClusterName(), serviceProperties.getPath(),
        serviceProperties.getLoadBalancerStrategyList(),
        strategyProperty, transportProperty,
        serviceProperties.getDegraderProperties(),
        serviceProperties.getPrioritizedSchemes(),
        serviceProperties.getBanned());

    _serviceRegistry.put(serviceProperties.getServiceName(), updatedServiceProperties);
    _clusterRegistry.put(serviceProperties.getClusterName(), clusterProperties);
    _uriRegistry.put(serviceProperties.getClusterName(), uriProperties);

    _delayGenerator = delayGenerator;
    _qpsGenerator = qpsGenerator;

    // construct loadBalancer and start it
    Map<String, LoadBalancerStrategyFactory<? extends LoadBalancerStrategy>> loadBalancerStrategyFactories =
        new HashMap<>();
    Map<String, TransportClientFactory> clientFactories = new HashMap<>();

    loadBalancerStrategyFactories.put("degrader", new DegraderLoadBalancerStrategyFactoryV3());
    DelayClientFactory delayClientFactory = new DelayClientFactory();
    clientFactories.put("http", delayClientFactory);
    clientFactories.put("https", delayClientFactory);

    SimpleLoadBalancerState loadBalancerState =
        new SimpleLoadBalancerState(_executorService,
            _uriRegistry,
            _clusterRegistry,
            _serviceRegistry,
            clientFactories,
            loadBalancerStrategyFactories);

    _loadBalancer = new SimpleLoadBalancer(loadBalancerState, 5, TimeUnit.SECONDS);

    FutureCallback<None> balancerCallback = new FutureCallback<None>();
    _loadBalancer.start(balancerCallback);
    balancerCallback.get();

    // schedule the RequestTask, which starts new set of requests repeatedly at the given interval
    _clockedExecutor.scheduleWithFixDelay(new RequestTask(updatedServiceProperties.getServiceName()),
        INIT_SCHEDULE_DELAY, SCHEDULE_INTERVAL);
  }

  public void shutdown() throws Exception
  {
    _clockedExecutor.shutdown();

    final CountDownLatch latch = new CountDownLatch(1);

    PropertyEventShutdownCallback callback = () -> latch.countDown();

    _loadBalancer.shutdown(callback);

    if (!latch.await(60, TimeUnit.SECONDS))
    {
      Assert.fail("unable to shutdown state");
    }

    _clockedExecutor.shutdown();
  }

  /**
   * Run the simulation until no task in the queue or stopped by explicitly call (Async)
   * @return
   */
  public Future<Void> run()
  {
    return run(0);
  }

  /**
   * Run the simulation for the provided duration (Async)
   * @param duration
   * @return
   */
  public Future<Void> run(long duration)
  {
    return _clockedExecutor.run(duration <= 0 ? 0 : _clockedExecutor._currentTimeMillis + duration);
  }

  /**
   * Run the simulation until the givenTime (Async)
   * @param expectedTime
   * @return
   */
  public Future<Void> runUntil(long expectedTime)
  {
    return _clockedExecutor.run(expectedTime);
  }

  /**
   * Run the simulation for the given duration (Sync)
   * @param duration
   */
  public void runWait(long duration)
  {
    Future<Void> running = run(duration);
    if (running != null)
    {
      try
      {
        running.get();
      }
      catch (InterruptedException | ExecutionException e)
      {
        _log.error("Simulation error: " + e);
      }
    }
  }

  public void stop()
  {
    _clockedExecutor.stop();
  }

  public Map<URI, Integer> getClientCounters()
  {
    return _clientCounters;
  }

  public Clock getClock()
  {
    return _clockedExecutor;
  }

  /**
   * Given a serviceName and partition number, return the hashring points for each URI
   * @param serviceName
   * @param partition
   * @return
   * @throws ServiceUnavailableException
   */
  public Map<URI, Integer> getPoints(String serviceName, int partition) throws ServiceUnavailableException
  {
    URI serviceUri = URI.create("d2://" + serviceName);
    Ring<URI> ring = _loadBalancer.getRings(serviceUri).get(partition);
    Map<URI, Integer> pointsMap = new HashMap<>();
    Iterator<URI> iter = ring.getIterator(0);

    iter.forEachRemaining(uri -> {
      pointsMap.compute(uri, (k, v) -> v == null ? 1: v + 1);
    });

    return pointsMap;
  }

  /**
   * Get the point for the given uri
   * @param serviceName
   * @param partition
   * @param uri
   * @return
   */
  public int getPoint(String serviceName, int partition, URI uri)
  {
    try
    {
      Map<URI, Integer> points = getPoints(serviceName, partition);
      return points.get(uri);
    }
    catch (ServiceUnavailableException e)
    {
      return 0;
    }
  }

  /**
   * Get the hitting percentage of the given uri (ie 'uri count'/'total inquiries')
   * @param uri
   * @return
   */
  public double getCountPercent(URI uri)
  {
    return getPercentageFromMap(uri, getClientCounters());
  }

  private double getPercentageFromMap(URI uri, Map<URI, Integer> map)
  {
    if (!map.containsKey(uri))
    {
      return 0.0;
    }
    Integer total = map.values().stream().reduce(0, Integer::sum);
    if (total == 0)
    {
      return 0.0;
    }
    return 1.0 * map.get(uri) / total;
  }

  /**
   * A runnable task to send out request
   */
  private class RequestTask implements Runnable
  {
    private String _serviceName;

    public RequestTask(String serviceName)
    {
      _serviceName = serviceName;
    }

    @Override
    public void run()
    {
      int qps = 0;
      Map<URI, Long> uriDelays = new HashMap<>();

      _clientCounters.clear();
      try
      {
        qps = _qpsGenerator.nextQPS();
      }
      catch(IllegalArgumentException e)
      {
        return;
      }

      for (int i = 0; i < qps; ++i)
      {
        // construct the requests
        URIRequest uriRequest = new URIRequest("d2://" + _serviceName + "/" + i);
        RestRequest restRequest = new RestRequestBuilder(uriRequest.getURI()).build();
        RequestContext requestContext = new RequestContext();

        RewriteClient client = null;
        try
        {
          client = (RewriteClient) _loadBalancer.getClient(restRequest, requestContext);
        }
        catch (ServiceUnavailableException e)
        {
          _log.error("Could not find service for request {}", restRequest.getURI());
          Assert.fail("Failed to find the service");
        }

        TransportCallback<RestResponse> restCallback = (response) -> {
          assertFalse(response.hasError());
          _log.debug("Got response for {} @ {}", response.getResponse(), _clockedExecutor.currentTimeMillis());
          // Do nothing for now for the response
        };

        URI clientUri = client.getUri();
        Long delay = uriDelays.get(clientUri);
        if (delay == null)
        {
          try
          {
            delay = _delayGenerator.nextDelay(clientUri);
          }
          catch (IllegalArgumentException e)
          {
            _log.error("Delay is not available for uri {}", clientUri);
            return;
          }

          uriDelays.put(clientUri, delay);
        }

        // use the requestContext to pass in the delay that is expected for this request
        requestContext.putLocalAttr("Delay", delay);

        _log.debug("Adding trackerclient for {}, delay {} ", clientUri, delay);

        // Increase the counter for each URI
        _clientCounters.compute(clientUri, (k, v) -> v == null ? 1 : v + 1);

        // send out the request
        client.restRequest(restRequest, requestContext, Collections.emptyMap(), restCallback);
      }
    }
  }

  /**
   * A simulated TransportClient, which schedules a delayed task to return the response.
   */
  @SuppressWarnings("unchecked")
  private static class DelayClientFactory implements TransportClientFactory
  {
    @Override
    public TransportClient getClient(Map<String, ? extends Object> properties)
    {
      ClockedExecutor clockedExecutor = (ClockedExecutor) properties.get("ClockedExecutor");

      return new DelayClient(clockedExecutor);
    }

    /**
     * DelayClient is a TransportClient that can delay the response with a given time
     */
    private class DelayClient implements TransportClient
    {
      private ClockedExecutor _clockedExecutor;

      DelayClient(ClockedExecutor executor)
      {
        _clockedExecutor = executor;
      }

      @Override
      public void streamRequest(StreamRequest request,
          RequestContext requestContext,
          Map<String, String> wireAttrs,
          TransportCallback<StreamResponse> callback)
      {
        throw new IllegalArgumentException("StreamRequest is not supported yet");
      }

      @Override
      public void restRequest(RestRequest request,
          RequestContext requestContext,
          Map<String, String> wireAttrs,
          TransportCallback<RestResponse> callback)
      {
        Long delay = (Long) requestContext.getLocalAttr("Delay");
        _clockedExecutor.schedule(new Runnable() {
          @Override
          public void run()
          {
            RestResponse restResponse = new RestResponseBuilder().setEntity(request.getURI().getRawPath().getBytes()).build();
            callback.onResponse(TransportResponseImpl.success(restResponse));
          }
        }, delay);
      }

      @Override
      public void shutdown(Callback<None> callback)
      {
        callback.onSuccess(None.none());
      }
    }

    @Override
    public void shutdown(Callback<None> callback)
    {
      callback.onSuccess(None.none());
    }
  }

  /**
   * A simulated service executor and clock
   */
  private class ClockedExecutor implements Clock, Executor
  {
    private volatile long _currentTimeMillis = 0l;
    private volatile Boolean _stopped = true;
    private volatile long _runUntil = 0l;
    private PriorityBlockingQueue<ClockedTask> _taskList = new PriorityBlockingQueue<>();
    private ExecutorService _executorService = Executors.newFixedThreadPool(1);

    public Future<Void> run(long untilTime)
    {
      if (!_stopped)
      {
        throw new IllegalArgumentException("Already Started!");
      }
      if (_taskList.isEmpty())
      {
        return null;
      }
      _stopped = false;
      _runUntil = untilTime;

      Future<Void> taskExecutor = _executorService.submit(() -> {
        while (!_stopped && !_taskList.isEmpty() && (_runUntil <= 0l || _runUntil > _currentTimeMillis))
        {
          ClockedTask task = _taskList.peek();
          long expectTime = task.getScheduledTime();

          if (expectTime > _runUntil)
          {
            _currentTimeMillis = _runUntil;
            break;
          }

          _taskList.remove();

          if (expectTime > _currentTimeMillis)
          {
            _currentTimeMillis = expectTime;
          }
          _log.debug("Processing task, total {}, time {}", _taskList.size(), _currentTimeMillis);
          task.run();
          if (task.repeatCount() > 0 && !_stopped)
          {
            task.reschedule(_currentTimeMillis);
            _taskList.add(task);
          }
        }
        _stopped = true;
        return null;
      });
      return taskExecutor;
    }

    public void schedule(Runnable cmd, long delay)
    {
      ClockedTask task = new ClockedTask(cmd, _currentTimeMillis + delay);
      _taskList.add(task);
    }

    public void scheduleWithFixDelay(Runnable cmd, long initDelay, long interval)
    {
      ClockedTask task = new ClockedTask(cmd, _currentTimeMillis + initDelay, interval, Long.MAX_VALUE);
      _taskList.add(task);
    }

    public void scheduleWithRepeat(Runnable cmd, long initDelay, long interval, long repeatTimes)
    {
      ClockedTask task = new ClockedTask(cmd, _currentTimeMillis + initDelay, interval, repeatTimes);
      _taskList.add(task);
    }

    @Override
    public void execute(Runnable cmd)
    {
      ClockedTask task = new ClockedTask(cmd, _currentTimeMillis);
      _taskList.add(task);
    }

    public void stop()
    {
      _stopped = true;
    }

    public void shutdown() throws Exception
    {
      _stopped = true;
      _executorService.shutdown();
    }

    @Override
    public long currentTimeMillis()
    {
      return _currentTimeMillis;
    }

    private class ClockedTask implements Comparable<ClockedTask>, Runnable
    {
      private long _expectTimeMillis = 0l;
      private long _interval = 0l;
      private Runnable _task;
      private long _repeatTimes = 0l;

      ClockedTask(Runnable task, long scheduledTime)
      {
        this(task, scheduledTime, 0l, 0l);
      }

      ClockedTask(Runnable task, long scheduledTime, long interval, long repeat)
      {
        _task = task;
        _expectTimeMillis = scheduledTime;
        _interval = interval;
        _repeatTimes = repeat;
      }

      @Override
      public int compareTo(ClockedTask other)
      {
        if (this._expectTimeMillis > other._expectTimeMillis)
        {
          return 1;
        } else if (this._expectTimeMillis < other._expectTimeMillis)
        {
          return -1;
        } else
        {
          return 0;
        }
      }

      @Override
      public void run()
      {
        _task.run();
      }

      long repeatCount()
      {
        return _repeatTimes;
      }

      long getScheduledTime()
      {
        return _expectTimeMillis;
      }

      void reschedule(long currentTime)
      {
        if (currentTime >= _expectTimeMillis && _repeatTimes-- > 0)
        {
          _expectTimeMillis += (_interval - (currentTime - _expectTimeMillis));
        }
      }
    }
  }
}