# 一键部署。
# 两阶段构建：先用 Maven 打出可执行 jar，再放进只有 JRE 的精简运行时镜像。

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷贝 pom 拉依赖，源码改动时不必重新下载依赖（层缓存）。
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

# 容器里按可用内存给堆设上限，避免把宿主机吃满；时区跟着系统走（compose 里传 TZ）。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Shanghai"

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
