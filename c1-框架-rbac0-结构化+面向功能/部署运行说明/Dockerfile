# 构建上下文为 C1 的 backend，先安装课程注解处理器，再编译应用。
ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-21
ARG JAVA_IMAGE=eclipse-temurin:21-jre-noble
FROM ${MAVEN_IMAGE} AS build
WORKDIR /workspace
COPY tools/clonefactory/pom.xml tools/clonefactory/LICENSE ./tools/clonefactory/
COPY tools/clonefactory/src ./tools/clonefactory/src
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -f tools/clonefactory/pom.xml install
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -DskipTests package

# 运行镜像使用非 root 用户。Compose 用基础镜像提供的 curl 探测 HTTP。
FROM ${JAVA_IMAGE}
WORKDIR /app
COPY --from=build /workspace/target/rbac0-0.1.0-SNAPSHOT.jar /app/rbac0.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/rbac0.jar"]
