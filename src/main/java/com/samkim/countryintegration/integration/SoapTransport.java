package com.samkim.countryintegration.integration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

@Component
public class SoapTransport {

    private static final Logger log =
            LoggerFactory.getLogger(SoapTransport.class);

    private final HttpClient client;
    private final URI endpoint;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final Duration requestTimeout;
    private final Duration importTimeout;
    private final ThreadLocal<Long> importDeadline = new ThreadLocal<>();

    @Autowired
    public SoapTransport(@Value("${country.soap.url}") String url) {
        this(url, Duration.ofSeconds(8), Duration.ofSeconds(30), Duration.ofSeconds(20));
    }

    SoapTransport(String url, Duration requestTimeout, Duration openDuration, Duration importTimeout) {
        this.requestTimeout = requestTimeout;
        this.importTimeout = importTimeout;
        this.endpoint = URI.create(url);

        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(300))
                .retryOnException(exception ->
                        exception instanceof SoapFailure failure
                                && failure.retryable)
                .build();

        this.retry = Retry.of("countrySoap", retryConfig);

        CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(openDuration)
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordException(exception ->
                        exception instanceof SoapFailure failure
                                && failure.retryable)
                .ignoreException(exception ->
                        exception instanceof SoapFailure failure
                                && !failure.retryable)
                .build();

        this.circuitBreaker =
                CircuitBreaker.of("countrySoap", breakerConfig);

        retry.getEventPublisher().onRetry(event ->
                log.warn(
                        "SOAP retry: attempt={} reason={}",
                        event.getNumberOfRetryAttempts(),
                        event.getLastThrowable().getMessage()));

        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn(
                        "SOAP circuit state changed: transition={}",
                        event.getStateTransition()));
    }

    public ImportBudget beginImport() {
        Long previous = importDeadline.get();
        long deadline = System.nanoTime() + importTimeout.toNanos();
        importDeadline.set(previous == null ? deadline : Math.min(previous, deadline));
        return new ImportBudget(previous);
    }

    public final class ImportBudget implements AutoCloseable {
        private final Long previous;
        private ImportBudget(Long previous) { this.previous = previous; }
        @Override public void close() {
            if (previous == null) importDeadline.remove();
            else importDeadline.set(previous);
        }
    }

    CircuitBreaker circuitBreaker() { return circuitBreaker; }

    private Duration remainingRequestTime() {
        Long deadline = importDeadline.get();
        if (deadline == null) return requestTimeout;
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new SoapFailure(HttpStatus.GATEWAY_TIMEOUT,
                    "Country import exceeded its SOAP time budget", false, null);
        }
        return Duration.ofNanos(Math.min(remaining, requestTimeout.toNanos()));
    }

    public String send(String envelope) {
        Supplier<String> retryableCall =
                Retry.decorateSupplier(retry, () -> sendOnce(envelope));

        Supplier<String> protectedCall =
                CircuitBreaker.decorateSupplier(
                        circuitBreaker, retryableCall);

        try {
            return protectedCall.get();

        } catch (CallNotPermittedException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Country information service is temporarily unavailable",
                    exception);

        } catch (SoapFailure exception) {
            log.warn(
                    "SOAP request failed: status={} reason={}",
                    exception.status.value(),
                    exception.getMessage());

            throw new ResponseStatusException(
                    exception.status,
                    exception.getMessage(),
                    exception);
        }
    }

    private String sendOnce(String envelope) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(remainingRequestTime())
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"\"")
                .POST(HttpRequest.BodyPublishers.ofString(
                        envelope, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                boolean retryable = response.statusCode() >= 500
                        || response.statusCode() == 429;

                throw new SoapFailure(
                        HttpStatus.BAD_GATEWAY,
                        "Country information service returned an unsuccessful response",
                        retryable,
                        null);
            }

            return response.body();

        } catch (HttpTimeoutException exception) {
            throw new SoapFailure(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "Country information service took too long to respond",
                    true,
                    exception);

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new SoapFailure(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Country information request was interrupted",
                    false,
                    exception);

        } catch (IOException exception) {
            throw new SoapFailure(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not connect to the country information service",
                    true,
                    exception);
        }
    }

    private static class SoapFailure extends RuntimeException {

        private final HttpStatus status;
        private final boolean retryable;

        private SoapFailure(
                HttpStatus status,
                String message,
                boolean retryable,
                Throwable cause) {
            super(message, cause);
            this.status = status;
            this.retryable = retryable;
        }
    }
}