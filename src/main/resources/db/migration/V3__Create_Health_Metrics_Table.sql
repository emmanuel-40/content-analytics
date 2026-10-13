-- Create health_metrics table
CREATE TABLE health_metrics (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    check_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL CHECK (status IN ('HEALTHY', 'DEGRADED', 'UNHEALTHY')),
    response_time_ms BIGINT,
    message TEXT,
    cpu_usage_percent FLOAT,
    memory_usage_percent FLOAT,
    disk_available_bytes BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_health_metrics_check_type ON health_metrics(check_type);
CREATE INDEX IF NOT EXISTS idx_health_metrics_created_at ON health_metrics(created_at);
CREATE INDEX IF NOT EXISTS idx_health_metrics_status ON health_metrics(status);

-- Comment
COMMENT ON TABLE health_metrics IS 'Application health check metrics for monitoring and alerting';
COMMENT ON COLUMN health_metrics.check_type IS 'Type of health check: database, redis, disk, system, api';
COMMENT ON COLUMN health_metrics.status IS 'Health status: HEALTHY, DEGRADED, UNHEALTHY';