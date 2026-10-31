FROM docker.io/library/eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY mvnw mvnw.cmd pom.xml checkstyle.xml ./
COPY .mvn .mvn
COPY src src
RUN ./mvnw -q -DskipTests package

FROM docker.io/library/eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
COPY --from=build /build/target/unified-service-scheduler-*.jar /app/app.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
