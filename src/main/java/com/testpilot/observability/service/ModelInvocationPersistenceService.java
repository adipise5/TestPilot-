package com.testpilot.observability.service;

import com.testpilot.observability.entity.ModelInvocationTrace;
import com.testpilot.observability.repository.ModelInvocationTraceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelInvocationPersistenceService {

    private final ModelInvocationTraceRepository traces;

    public ModelInvocationPersistenceService(ModelInvocationTraceRepository traces) {
        this.traces = traces;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ModelInvocationTrace save(ModelInvocationTrace trace) {
        return traces.save(trace);
    }
}
