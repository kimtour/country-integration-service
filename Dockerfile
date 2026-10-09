FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/main/ src/main/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre-noble
WORKDIR /app
RUN groupadd --gid 10001 appgroup \
    && useradd --uid 10001 --gid appgroup \
       --no-create-home --shell /usr/sbin/nologin appuser
COPY --from=build --chown=appuser:appgroup \
    /workspace/target/country-integration-service-0.0.1-SNAPSHOT.jar /app/app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
