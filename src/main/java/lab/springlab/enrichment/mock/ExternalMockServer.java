package lab.springlab.enrichment.mock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.Locale;

public class ExternalMockServer {

    public static void main(String[] args) {
        Config config = Config.fromArgs(args);
        String okBody = buildBody(config.payloadSize);
        String errorBody = "{\"error\":\"upstream failure\"}";

        WireMockConfiguration options = WireMockConfiguration.options()
                .port(config.port)
                .extensions(new RandomErrorResponseTransformer(config.errorRate, config.delayMs, okBody, errorBody));

        WireMockServer server = new WireMockServer(options);
        server.stubFor(post(urlEqualTo("/enrich"))
                .willReturn(aResponse().withTransformers("random-error")));

        server.start();
        System.out.println("WireMock running on port " + config.port
                + " delayMs=" + config.delayMs
                + " errorRate=" + config.errorRate
                + " payloadSize=" + config.payloadSize);

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }

    private static String buildBody(int payloadSize) {
        StringBuilder builder = new StringBuilder(payloadSize + 20);
        builder.append("{\"data\":\"");
        for (int i = 0; i < payloadSize; i++) {
            builder.append('x');
        }
        builder.append("\"}");
        return builder.toString();
    }

    private static class Config {
        private final int port;
        private final int delayMs;
        private final double errorRate;
        private final int payloadSize;

        private Config(int port, int delayMs, double errorRate, int payloadSize) {
            this.port = port;
            this.delayMs = delayMs;
            this.errorRate = errorRate;
            this.payloadSize = payloadSize;
        }

        private static Config fromArgs(String[] args) {
            int port = 9091;
            int delayMs = 20;
            double errorRate = 0.0;
            int payloadSize = 64;

            for (String arg : args) {
                if (arg.startsWith("--port=")) {
                    port = Integer.parseInt(arg.substring("--port=".length()));
                } else if (arg.startsWith("--delay-ms=")) {
                    delayMs = Integer.parseInt(arg.substring("--delay-ms=".length()));
                } else if (arg.startsWith("--error-rate=")) {
                    errorRate = Double.parseDouble(arg.substring("--error-rate=".length()));
                } else if (arg.startsWith("--payload-size=")) {
                    payloadSize = Integer.parseInt(arg.substring("--payload-size=".length()));
                }
            }

            if (errorRate < 0.0 || errorRate > 1.0) {
                throw new IllegalArgumentException("error-rate must be between 0 and 1");
            }

            return new Config(port, delayMs, errorRate, payloadSize);
        }
    }
}
