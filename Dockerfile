# ---- build stage: compile and package the library on JDK 25 ----
FROM maven:3.10-eclipse-temurin-25-noble AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
# sources/javadoc jars are skipped so the artifact glob below matches exactly
# one file; tests run in CI (.github/workflows/ci.yml), not in the image build
RUN mvn -B -ntp -DskipTests -Dmaven.source.skip=true -Dmaven.javadoc.skip=true package

# ---- runtime stage: the demo file server on a JRE ----
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/target/httpserver-*.jar httpserver.jar
# the demo mains (SimpleFileServer, FileServerHandler, ...) are test sources
COPY --from=build /src/target/test-classes ./test-classes
RUN mkdir -p /app/fileserver
EXPOSE 8080
# demo endpoints: /files (static content), /echo, /devnull, / (hello world)
CMD ["java", "-cp", "httpserver.jar:test-classes", "SimpleFileServer", "fileserver", "8080", "fileserver/logfile.txt"]
