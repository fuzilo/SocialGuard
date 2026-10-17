FROM eclipse-temurin:24-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
RUN ./mvnw -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:24-jre
WORKDIR /app
COPY --from=build /workspace/target/SocialGuard-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
