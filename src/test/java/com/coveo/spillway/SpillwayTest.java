package com.coveo.spillway;

import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class SpillwayTest {

  private static int ONE_MILLION = 1000000;

  private class User {

    private String name;
    private String ip;

    public User(String name, String ip) {
      this.name = name;
      this.ip = ip;
    }

    public String getName() {
      return name;
    }

    public String getIp() {
      return ip;
    }
  }

  private User john = new User("john", "127.0.0.1");
  private User gina = new User("gina", "127.0.0.1");

  private SpillwayFactory factory;

  @Before
  public void setup() {
    factory = new SpillwayFactory(new InMemoryStorage());
  }

  @Test
  public void simpleLimit() {
    Limit<User> limit1 =
        LimitBuilder.of("perUser", User::getName).to(2).per(Duration.ofHours(1)).build();
    Spillway<User> spillway = factory.enforce("testResource", limit1);

    assertThat(spillway.tryCall(john)).isTrue();
    assertThat(spillway.tryCall(john)).isTrue();
    assertThat(spillway.tryCall(john)).isFalse(); // Third tryCall fails
  }

  @Test
  public void multipleLimitsWithOverlap() {
    Limit<User> limit1 =
        LimitBuilder.of("perUser", User::getName).to(5).per(Duration.ofHours(1)).build();
    Limit<User> limit2 =
        LimitBuilder.of("perUser", User::getName).to(1).per(Duration.ofSeconds(1)).build();
    Spillway<User> spillway = factory.enforce("testResource", limit1, limit2);

    assertThat(spillway.tryCall(john)).isTrue();
    assertThat(spillway.tryCall(john)).isFalse(); // 2nd tryCall fails
  }

  @Test
  public void multipleLimitsNoOverlap() {
    Limit<User> ipLimit =
        LimitBuilder.of("perIp", User::getIp).to(1).per(Duration.ofHours(1)).build();
    Limit<User> userLimit =
        LimitBuilder.of("perUser", User::getName).to(1).per(Duration.ofHours(1)).build();
    Spillway<User> spillway = factory.enforce("testResource", ipLimit, userLimit);

    assertThat(spillway.tryCall(john)).isTrue();
    assertThat(spillway.tryCall(gina)).isFalse(); // Gina is on John's IP.
  }

  @Test
  public void oneMillionConcurrentRequestsWith100Threads() throws InterruptedException {
    Limit<User> ipLimit =
        LimitBuilder.of("perIp", User::getIp).to(ONE_MILLION).per(Duration.ofHours(1)).build();
    Spillway<User> spillway = factory.enforce("testResource", ipLimit);

    ExecutorService threadPool = Executors.newFixedThreadPool(100);

    AtomicInteger counter = new AtomicInteger(0);
    // We do ONE MILLION + 1 iterations and check to make sure that the counter was not incremented more than expected.
    for (int i = 0; i < ONE_MILLION + 1; i++) {
      threadPool.submit(
          () -> {
            boolean canCall = spillway.tryCall(john);
            if (canCall) {
              counter.incrementAndGet();
            }
          });
    }
    threadPool.shutdown();
    threadPool.awaitTermination(1, TimeUnit.MINUTES);

    assertThat(counter.get()).isEqualTo(ONE_MILLION);
  }

  @Test
  public void multipleResourcesEachHaveTheirOwnLimit() {
    Limit<User> userLimit =
        LimitBuilder.of("perUser", User::getName).to(1).per(Duration.ofHours(1)).build();
    Spillway<User> spillway1 = factory.enforce("testResource1", userLimit);
    Spillway<User> spillway2 = factory.enforce("testResource2", userLimit);

    assertThat(spillway1.tryCall(john)).isTrue();
    assertThat(spillway2.tryCall(john)).isTrue();
    assertThat(spillway1.tryCall(john)).isFalse();
    assertThat(spillway2.tryCall(john)).isFalse();
  }

  @Test
  public void canUseDefaultPropertyExtractor() {
    Limit<String> userLimit = LimitBuilder.of("perUser").to(1).per(Duration.ofHours(1)).build();
    Spillway<String> spillway = factory.enforce("testResource", userLimit);

    assertThat(spillway.tryCall(john.getName())).isTrue();
    assertThat(spillway.tryCall(john.getName())).isFalse();
  }

  @Test
  public void canBeNotifiedWhenLimitIsExceeded() {
    LimitExceededCallback callback = mock(LimitExceededCallback.class);
    Limit<User> userLimit =
        LimitBuilder.of("perUser", User::getName)
            .to(1)
            .per(Duration.ofHours(1))
            .withExceededCallback(callback)
            .build();
    Spillway<User> spillway = factory.enforce("testResource", userLimit);

    spillway.tryCall(john);
    spillway.tryCall(john);

    verify(callback).handleExceededLimit(userLimit.getDefinition(), john);
  }

  @Test
  public void callThrowsAnExceptionWhichContainsAllTheDetails()
      throws SpillwayLimitExceededException {
    Limit<User> userLimit =
        LimitBuilder.of("perUser", User::getName).to(1).per(Duration.ofHours(1)).build();
    Spillway<User> spillway = factory.enforce("testResource", userLimit);

    spillway.call(john);
    try {
      spillway.call(john);
      fail("Expected an exception!");
    } catch (SpillwayLimitExceededException ex) {
      assertThat(ex.getExceededLimits()).hasSize(1);
      assertThat(ex.getExceededLimits().get(0)).isEqualTo(userLimit.getDefinition());
      assertThat(ex.toString())
          .isEqualTo(
              "com.coveo.spillway.SpillwayLimitExceededException: Limits [perUser[1 calls/PT1H]] exceeded.");
      assertThat(ex.getContext()).isEqualTo(john);
    }
  }

  @Test
  public void callThrowsForMultipleBreachedLimits() throws SpillwayLimitExceededException {
    Limit<User> userLimit =
        LimitBuilder.of("perUser", User::getName).to(1).per(Duration.ofHours(1)).build();
    Limit<User> ipLimit =
        LimitBuilder.of("perIp", User::getIp).to(1).per(Duration.ofHours(1)).build();

    Spillway<User> spillway = factory.enforce("testResource", userLimit, ipLimit);

    spillway.call(john);
    try {
      spillway.call(john);
      fail("Expected an exception!");
    } catch (SpillwayLimitExceededException ex) {
      assertThat(ex.getExceededLimits()).hasSize(2);
      assertThat(ex.getExceededLimits().get(0)).isEqualTo(userLimit.getDefinition());
      assertThat(ex.getExceededLimits().get(1)).isEqualTo(ipLimit.getDefinition());
      assertThat(ex.toString())
          .isEqualTo(
              "com.coveo.spillway.SpillwayLimitExceededException: Limits [perUser[1 calls/PT1H], perIp[1 calls/PT1H]] exceeded.");
      assertThat(ex.getContext()).isEqualTo(john);
    }
  }

  @Test
  public void bucketChangesWhenTimePasses() throws InterruptedException {
    Limit<User> userLimit =
        LimitBuilder.of("perUser", User::getName).to(1).per(Duration.ofSeconds(1)).build();
    Spillway<User> spillway = factory.enforce("testResource", userLimit);

    assertThat(spillway.tryCall(john)).isTrue();
    assertThat(spillway.tryCall(john)).isFalse();

    Thread.sleep(2000); // Sleep two seconds to ensure that we bump to another bucket

    assertThat(spillway.tryCall(john)).isTrue();
  }

  @Test
  public void canGetCurrentLimitStatus() throws SpillwayLimitExceededException {
    Limit<User> userLimit =
        LimitBuilder.of("perUser", User::getName).to(2).per(Duration.ofHours(1)).build();
    Spillway<User> spillway = factory.enforce("testResource", userLimit);

    spillway.call(john);
    spillway.call(gina);
    spillway.call(john);

    Map<LimitKey, Integer> limitStatuses = spillway.debugCurrentLimitCounters();
    assertThat(limitStatuses).hasSize(2);
    assertThat(limitStatuses.toString()).isNotEmpty();

    Optional<LimitKey> johnKey =
        limitStatuses
            .keySet()
            .stream()
            .filter(key -> key.getProperty().equals(john.getName()))
            .findFirst();
    assertThat(johnKey.isPresent()).isTrue();
    assertThat(limitStatuses.get(johnKey.get())).isEqualTo(2);

    Optional<LimitKey> ginaKey =
        limitStatuses
            .keySet()
            .stream()
            .filter(key -> key.getProperty().equals(gina.getName()))
            .findFirst();
    assertThat(ginaKey.isPresent()).isTrue();
    assertThat(limitStatuses.get(ginaKey.get())).isEqualTo(1);
  }

  @Test
  public void ifCallbackThrowsWeIgnoreThatCallbackAndContinue()
      throws SpillwayLimitExceededException {
    LimitExceededCallback callbackThatIsOkay = mock(LimitExceededCallback.class);
    LimitExceededCallback callbackThatThrows = mock(LimitExceededCallback.class);
    doThrow(RuntimeException.class)
        .when(callbackThatThrows)
        .handleExceededLimit(any(LimitDefinition.class), any(Object.class));
    Limit<User> ipLimit1 =
        LimitBuilder.of("perIp1", User::getIp)
            .to(1)
            .per(Duration.ofHours(1))
            .withExceededCallback(callbackThatThrows)
            .build();
    Limit<User> userLimit =
        LimitBuilder.of("perUser", User::getName)
            .to(1)
            .per(Duration.ofHours(1))
            .withExceededCallback(callbackThatIsOkay)
            .build();
    Limit<User> ipLimit2 =
        LimitBuilder.of("perIp2", User::getIp)
            .to(1)
            .per(Duration.ofHours(1))
            .withExceededCallback(callbackThatThrows)
            .build();
    Spillway<User> spillway = factory.enforce("testResource", ipLimit1, userLimit, ipLimit2);

    spillway.tryCall(john);
    spillway.tryCall(john);

    verify(callbackThatThrows).handleExceededLimit(ipLimit1.getDefinition(), john);
    verify(callbackThatIsOkay).handleExceededLimit(userLimit.getDefinition(), john);
    verify(callbackThatThrows).handleExceededLimit(ipLimit2.getDefinition(), john);
  }
}
