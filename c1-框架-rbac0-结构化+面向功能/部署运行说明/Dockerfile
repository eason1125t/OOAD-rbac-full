# 从 backend 源码构建可执行 JAR，运行阶段只包含 Java 和应用。
ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-21
ARG JAVA_IMAGE=eclipse-temurin:21-jre-noble
FROM ${MAVEN_IMAGE} AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -DskipTests package

FROM ${JAVA_IMAGE}
WORKDIR /app
COPY --from=build /workspace/target/rbac0-0.1.0-SNAPSHOT.jar /app/rbac0.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/rbac0.jar"]
