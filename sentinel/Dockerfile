FROM eclipse-temurin:8-jre
WORKDIR /app
COPY sentinel-dashboard.jar /app/
EXPOSE 8858
ENTRYPOINT ["java", "-Dserver.port=8858", "-Dcsp.sentinel.dashboard.server=0.0.0.0:8858", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/sentinel-dashboard.jar"]
