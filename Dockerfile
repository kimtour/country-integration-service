FROM eclipse-temurin:25-jre-noble

WORKDIR /app

RUN groupadd --gid 10001 appgroup \
    && useradd --uid 10001 --gid appgroup \
       --no-create-home --shell /usr/sbin/nologin appuser

COPY --chown=appuser:appgroup \
    target/country-integration-service-0.0.1-SNAPSHOT.jar \
    /app/app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
