package lab.springlab.enrichment.mock;

import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseTransformer;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.Response;
import java.util.concurrent.ThreadLocalRandom;

public class RandomErrorResponseTransformer extends ResponseTransformer {

    private final double errorRate;
    private final int delayMs;
    private final String okBody;
    private final String errorBody;

    public RandomErrorResponseTransformer(double errorRate, int delayMs, String okBody, String errorBody) {
        this.errorRate = errorRate;
        this.delayMs = delayMs;
        this.okBody = okBody;
        this.errorBody = errorBody;
    }

    @Override
    public Response transform(Request request,
                              Response response,
                              FileSource files,
                              Parameters parameters) {
        boolean isError = ThreadLocalRandom.current().nextDouble() < errorRate;
        HttpHeaders headers = new HttpHeaders(new HttpHeader("Content-Type", "application/json"));
        if (isError) {
            return Response.response()
                    .status(500)
                    .headers(headers)
                    .body(errorBody)
                    .incrementInitialDelay(delayMs)
                    .build();
        }
        return Response.response()
                .status(200)
                .headers(headers)
                .body(okBody)
                .incrementInitialDelay(delayMs)
                .build();
    }

    @Override
    public String getName() {
        return "random-error";
    }

    @Override
    public boolean applyGlobally() {
        return false;
    }
}
