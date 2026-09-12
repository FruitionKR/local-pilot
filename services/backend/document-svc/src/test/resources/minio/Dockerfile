FROM golang:1.24.8-alpine AS build
# MinIO RELEASE.2025-10-15T17-29-55Z의 소스 커밋을 고정한다.
RUN CGO_ENABLED=0 go install -trimpath github.com/minio/minio@9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a

FROM alpine:3.22
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /go/bin/minio /usr/local/bin/minio
ENTRYPOINT ["/usr/local/bin/minio"]
