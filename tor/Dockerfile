# Tor client for the crawler (fix-real-infra-findings D4). Built here instead of pulling a
# third-party image: dperson/torproxy stalled at "Bootstrapped 45%" once the network
# started requiring newer relay protocols. Bump the Alpine tag to get a newer Tor.
FROM alpine:3.22
RUN apk add --no-cache tor \
 && printf 'SocksPort 0.0.0.0:9050\nDataDirectory /var/lib/tor\nLog notice stdout\nLog notice file /var/lib/tor/notices.log\n' > /etc/tor/torrc
USER tor
EXPOSE 9050
# Truncate the log on every start so the healthcheck never sees a previous run's 100%.
CMD ["sh", "-c", ": > /var/lib/tor/notices.log && exec tor -f /etc/tor/torrc"]
