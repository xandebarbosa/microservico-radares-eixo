package com.coruja.config;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
@EnableScheduling
@Slf4j
public class SchedulerConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(taskSchedulerEixo());
    }

    @Bean(name = "taskSchedulerEixo")
    public ThreadPoolTaskScheduler taskSchedulerEixo() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("coruja-task-eixo-");

        // Impede que uma exceção em um job pare o agendador inteiro
        scheduler.setErrorHandler(t -> log.error("[Scheduler] Erro inesperado: ", t));

        // Garante que o app espere os jobs terminarem ao desligar (Graceful Shutdown)
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);

        scheduler.initialize();
        return scheduler;
    }
}
