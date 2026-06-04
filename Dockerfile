# ==================== 多阶段构建 Dockerfile ====================
# 阶段1：Maven 构建（JDK 8，使用阿里云 Maven 镜像加速）
FROM maven:3.9.6-eclipse-temurin-8 AS build
WORKDIR /app

# 配置阿里云 Maven 镜像，加速依赖下载
RUN mkdir -p /root/.m2 && \
    echo '<?xml version="1.0" encoding="UTF-8"?><settings><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><name>aliyun</name><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > /root/.m2/settings.xml

# 拷贝源码
COPY . .

# 编译打包（跳过测试）
RUN mvn clean package -Dmaven.test.skip=true -B -q

# 阶段2：运行时镜像（JRE 8）
FROM eclipse-temurin:8-jre
ARG SERVICE_NAME
WORKDIR /app
COPY --from=build /app/${SERVICE_NAME}/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
