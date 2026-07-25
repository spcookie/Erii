# Stage 1: Build erii-core (Kotlin/Gradle)
FROM eclipse-temurin:17 AS kotlin-build
WORKDIR /build
COPY . .
RUN ./gradlew :erii-core:installDist --no-daemon --quiet

# Stage 2: Build erii-cli (Go)
FROM golang:1.25 AS go-build
WORKDIR /build
COPY erii-cli/go.mod erii-cli/go.sum ./
RUN go mod download
COPY erii-cli/ .
RUN go run github.com/magefile/mage build linux amd64

# Stage 3: Final runtime image
FROM eclipse-temurin:17

ENV TZ=Asia/Shanghai

WORKDIR /erii

# Copy erii-core libs (for classpath)
COPY --from=kotlin-build /build/erii-core/build/install/erii-core/lib ./lib

# Copy erii-cli Go binary
COPY --from=go-build /build/build/erii-linux/amd64/erii-cli ./erii-cli
RUN chmod +x ./erii-cli

# Copy config files
COPY erii-cli/conf ./conf
COPY erii-cli/opts ./opts
COPY erii-cli/.conf ./.conf

# Copy entrypoint
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

VOLUME /erii/conf
VOLUME /erii/store
VOLUME /erii/plugins
VOLUME /erii/.conf
VOLUME /erii/.erii

EXPOSE 8000 8080 8082 8180

ENTRYPOINT ["/entrypoint.sh"]
