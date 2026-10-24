FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /crawler ./cmd/crawler

FROM gcr.io/distroless/static-debian12
COPY --from=build /crawler /crawler
COPY config.yaml /config.yaml
# Empty placeholder; mount the real list over it at deploy.
COPY denylist/banned-hosts.md5 /denylist/banned-hosts.md5
USER nonroot:nonroot
ENTRYPOINT ["/crawler"]
