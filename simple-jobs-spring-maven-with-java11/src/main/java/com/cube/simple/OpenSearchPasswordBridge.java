package com.cube.simple;

import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.iv.NoIvGenerator;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

public class OpenSearchPasswordBridge
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    @Override
    public int getOrder() {
        // After EnvironmentPostProcessors (+10), Before LoggingApplicationListener (+20)
        return Ordered.HIGHEST_PRECEDENCE + 15;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        try {
            ConfigurableEnvironment env = event.getEnvironment();
            String rawPassword = env.getProperty("opensearch.password", "");

            if (rawPassword.startsWith("ENC(") && rawPassword.endsWith(")")) {
                String encKey = resolveEncryptorKey(env);
                if (encKey != null && !encKey.isEmpty()) {
                    String algorithm = env.getProperty("jasypt.encryptor.algorithm", "PBEWithMD5AndDES");
                    String ivGenClass = env.getProperty("jasypt.encryptor.iv-generator-classname", "");
                    String cipherText = rawPassword.substring(4, rawPassword.length() - 1);

                    StandardPBEStringEncryptor enc = new StandardPBEStringEncryptor();
                    enc.setPassword(encKey);
                    enc.setAlgorithm(algorithm);
                    if ("org.jasypt.iv.NoIvGenerator".equals(ivGenClass)) {
                        enc.setIvGenerator(new NoIvGenerator());
                    }
                    rawPassword = enc.decrypt(cipherText);
                }
            }

            System.setProperty("OPENSEARCH_PASS_RESOLVED", rawPassword);
        } catch (Exception ignored) {
        }
    }

    private String resolveEncryptorKey(ConfigurableEnvironment env) {
        String key = env.getProperty("jasypt.encryptor.password", "");
        if (key.isEmpty()) {
            String envKey = System.getenv("JASYPT_ENCRYPTOR_PASSWORD");
            if (envKey != null && !envKey.isEmpty()) {
                key = envKey;
            }
        }
        return key;
    }
}
