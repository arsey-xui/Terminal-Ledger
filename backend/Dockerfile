# --- 构建阶段：用 Maven + JDK17 编译 ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 先拷依赖清单拉取依赖（利用层缓存，改代码时不必重下依赖）
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package

# --- 运行阶段：仅保留 JRE ---
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

ENV SERVER_PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
