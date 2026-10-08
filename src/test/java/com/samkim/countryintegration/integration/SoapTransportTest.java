package com.samkim.countryintegration.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SoapTransportTest {

    private HttpServer server;

    private final AtomicInteger requestCount = new AtomicInteger();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void retriesAfterTemporaryFailureAndReturnsSuccessfulResponse()
            throws IOException {

        String url = startServer(503, 200);
        SoapTransport transport = new SoapTransport(url);

        String response = transport.send("<request/>");

        assertEquals("<response/>", response);
        assertEquals(2, requestCount.get());
    }

    @Test
    void doesNotRetryAnHttp400Response() throws IOException {

        String url = startServer(400);
        SoapTransport transport = new SoapTransport(url);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> transport.send("<request/>")
        );

        assertEquals(502, exception.getStatusCode().value());
        assertEquals(1, requestCount.get());
    }

    @Test
    void opensCircuitAfterRepeatedFailuresAndBlocksNextRequest()
            throws IOException {

        String url = startServer(503);
        SoapTransport transport = new SoapTransport(url);

        // Each call attempts the request twice.
        // Five failed calls reach the circuit breaker's minimum.
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThrows(
                    ResponseStatusException.class,
                    () -> transport.send("<request/>")
            );
        }

        assertEquals(10, requestCount.get());

        // The open circuit should reject this call immediately.
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> transport.send("<request/>")
        );

        assertEquals(503, exception.getStatusCode().value());

        // No additional HTTP request should reach the server.
        assertEquals(10, requestCount.get());
    }

    private String startServer(int... responseStatuses)
            throws IOException {

        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        server.createContext("/soap", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();

                int requestIndex = requestCount.getAndIncrement();

                int statusIndex = Math.min(
                        requestIndex,
                        responseStatuses.length - 1
                );

                int status = responseStatuses[statusIndex];

                byte[] body = "<response/>".getBytes(
                        StandardCharsets.UTF_8
                );

                exchange.getResponseHeaders().set(
                        "Content-Type",
                        "text/xml; charset=UTF-8"
                );

                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });

        server.start();

        return "http://127.0.0.1:"
                + server.getAddress().getPort()
                + "/soap";
    }
}