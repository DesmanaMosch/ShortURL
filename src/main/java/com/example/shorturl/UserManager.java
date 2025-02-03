package org.example.shorturl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.Scanner;
import java.util.UUID;

public class UserManager {
    private String uuid;
    private static final String DB_URL = "jdbc:sqlite:" + Paths.get(getUserDataFolder(), "app_data.db").toString();
    private static final Logger logger = LoggerFactory.getLogger(UserManager.class);
    private final Scanner scanner = new Scanner(System.in);

    public UserManager() {
        createDatabaseIfNotExists();
        this.uuid = loadOrGenerateUUID();
    }

    private String loadOrGenerateUUID() {
        String localUUID = loadUUIDFromLocalStore();
        if (localUUID == null || !isUUIDValid(localUUID)) {
            logger.info("Локальный UUID недействителен или не найден. Генерируем новый.");
            localUUID = generateAndSaveUUID();
        } else {
            logger.info("Авторизация пользователя с UUID: " + localUUID);
        }

        if (confirmUserChange()) {
            localUUID = changeUser();
        }
        return localUUID;
    }

    private boolean confirmUserChange() {
        System.out.print("Сменить пользователя? (y/n): ");
        return scanner.nextLine().trim().equalsIgnoreCase("y");
    }

    private String changeUser() {
        System.out.print("Введите UUID пользователя: ");
        String newUUID = scanner.nextLine().trim();
        if (isUUIDValid(newUUID)) {
            saveUUIDToLocalStore(newUUID);
            logger.info("Авторизация пользователя с UUID: " + newUUID);
            return newUUID;
        } else {
            logger.info("UUID недействителен");
            return loadOrGenerateUUID();
        }
    }

    private String generateAndSaveUUID() {
        String newUUID = UUID.randomUUID().toString();
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO users (uuid) VALUES (?)")) {
            preparedStatement.setString(1, newUUID);
            preparedStatement.executeUpdate();
            saveUUIDToLocalStore(newUUID);
            logger.info("Создан новый пользователь с UUID: " + newUUID);
        } catch (SQLException e) {
            logger.error("Ошибка при добавлении UUID в базу данных: " + e.getMessage(), e);
            return null;
        }
        return newUUID;
    }

    private boolean isUUIDValid(String uuidToCheck) {
        if (uuidToCheck == null || uuidToCheck.trim().isEmpty()) {
            return false;
        }
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement preparedStatement = connection.prepareStatement("SELECT COUNT(*) FROM users WHERE uuid = ?")) {
            preparedStatement.setString(1, uuidToCheck);
            ResultSet resultSet = preparedStatement.executeQuery();
            return resultSet.next() && resultSet.getInt(1) > 0;
        } catch (SQLException e) {
            logger.error("Ошибка при проверке UUID в базе данных: " + e.getMessage(), e);
            return false;
        }
    }

    private String loadUUIDFromLocalStore() {
        String pathToFile = Paths.get(getUserDataFolder(), "config.txt").toString();
        File file = new File(pathToFile);
        if (file.exists()) {
            try (Scanner scanner = new Scanner(file)) {
                if (scanner.hasNextLine()) {
                    return scanner.nextLine();
                }
            } catch (IOException e) {
                logger.error("Ошибка при чтении файла UUID: " + e.getMessage(), e);
            }
        }
        return null;
    }

    private void saveUUIDToLocalStore(String uuid) {
        String pathToFile = Paths.get(getUserDataFolder(), "config.txt").toString();
        try (FileWriter fileWriter = new FileWriter(pathToFile)) {
            fileWriter.write(uuid);
        } catch (IOException e) {
            logger.error("Ошибка при сохранении UUID в файл: " + e.getMessage(), e);
        }
    }

    public static String getUserDataFolder() {
        String userHome = System.getProperty("user.home");
        Path dataFolder = Paths.get(userHome, ".short_links_app");
        if (!dataFolder.toFile().exists()) {
            dataFolder.toFile().mkdirs();
        }
        return dataFolder.toString();
    }

    private void createDatabaseIfNotExists() {
        try (Connection connection = DriverManager.getConnection(DB_URL)) {
            if (connection != null) {
                String createUsersTableSQL = "CREATE TABLE IF NOT EXISTS users (uuid TEXT PRIMARY KEY)";
                String createLinksTableSQL = "CREATE TABLE IF NOT EXISTS short_urls (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "uuid TEXT," +
                        "short_url TEXT UNIQUE," +
                        "original_url TEXT," +
                        "expiration_time TIMESTAMP," +
                        "max_clicks INTEGER," +
                        "clicks INTEGER DEFAULT 0)";
                try (Statement statement = connection.createStatement()) {
                    statement.execute(createUsersTableSQL);
                    statement.execute(createLinksTableSQL);
                    logger.info("База данных и таблицы успешно созданы.");
                }
            }
        } catch (SQLException e) {
            logger.error("Ошибка при создании базы данных: " + e.getMessage(), e);
            throw new RuntimeException("Не удалось создать базу данных.", e);
        }
    }

    public String getCurrentUserUUID() {
        return uuid;
    }
}