package com.coruja.config;

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
public class SchedulerConfig implements SchedulingConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerConfig.class);

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        // Força o registrar a usar o nosso Bean customizado
        taskRegistrar.setTaskScheduler(taskScheduler());
    }

    // A mágica acontece aqui: Expor como @Bean garante que o Spring
    // substitua o agendador padrão em todo o contexto da aplicação.
    @Bean(name = "taskSchedulerEixo")
    private ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("coruja-task-eixo-");

        // Impede que uma exceção em um job pare o agendador inteiro
        scheduler.setErrorHandler(t -> logger.error("Erro inesperado no Scheduler: ", t));

        // Garante que o app espere os jobs terminarem ao desligar
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);

        scheduler.initialize();
        return scheduler;
    }
}
