FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /crawler ./cmd/crawler

FROM gcr.io/distroless/static-debian12
COPY --from=build /crawler /crawler
COPY config.yaml /config.yaml
USER nonroot:nonroot
ENTRYPOINT ["/crawler"]
