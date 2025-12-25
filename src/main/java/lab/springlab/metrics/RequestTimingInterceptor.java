package lab.springlab.metrics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RequestTimingInterceptor implements HandlerInterceptor {

    private static final String START_ATTRIBUTE = RequestTimingInterceptor.class.getName() + ".start";
    private final RequestTimingRegistry registry;

    public RequestTimingInterceptor(RequestTimingRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTRIBUTE, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        Object started = request.getAttribute(START_ATTRIBUTE);
        if (started instanceof Long startNs) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            registry.record(request.getRequestURI(), durationMs);
        }
    }
}
