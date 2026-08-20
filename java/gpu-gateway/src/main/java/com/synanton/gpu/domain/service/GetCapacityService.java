package com.synanton.gpu.domain.service;

import com.synanton.gpu.domain.port.in.GetCapacityUseCase;
import com.synanton.gpu.domain.port.out.ExecutionRepository;
import com.synanton.gpu.domain.port.out.ModelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Advisory capacity query. Does not reserve GPU capacity. */
@Service
@RequiredArgsConstructor
public class GetCapacityService implements GetCapacityUseCase {

    private final ModelRepository modelRepository;
    private final ExecutionRepository executionRepository;

    @Override
    public Optional<CapacityInfo> getCapacity(String modelId) {
        return modelRepository.getCapabilities(modelId)
                .map(capabilities -> {
                    int activeCount = executionRepository.countActiveExecutions(modelId);
                    int available = Math.max(0, capabilities.concurrencyLimit() - activeCount);
                    double fraction = capabilities.concurrencyLimit() > 0
                            ? (double) available / capabilities.concurrencyLimit()
                            : 0.0;
                    boolean healthy = fraction > 0.0;
                    return new CapacityInfo(capabilities, activeCount, true, healthy);
                });
    }
}
