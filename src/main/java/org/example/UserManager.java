import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.Scanner;
import java.util.UUID;

public class UserManager {

    private String uuid;
    // Замените на вашу строку подключения к MySQL
    private static final String DB_URL = "jdbc:mysql://localhost:3306/your_database";
    private static final String DB_USER = "your_user";
    private static final String DB_PASSWORD = "your_password";
    private final Scanner scanner = new Scanner(System.in);

    private static final Logger logger = LoggerFactory.getLogger(UserManager.class);

    public UserManager() {
        createDatabaseIfNotExists();
        this.uuid = loadOrGenerateUUID();
    }

    private String loadOrGenerateUUID() {
        String localUUID = loadUUIDFromLocalStore();
        if (localUUID == null) {
            localUUID = generateAndSaveUUID();
        } else {
            if (!isUUIDValid(localUUID)) {
                logger.info("Локальный UUID недействителен. Генерируем новый.");
                localUUID = generateAndSaveUUID();
            } else {
                logger.info("Авторизация пользователя с UUID: " + localUUID);
            }
            if(confirmUserChange()){
                localUUID = changeUser();
            }
        }
        return localUUID;
    }
    private boolean confirmUserChange(){
        System.out.println("Сменить пользователя? (y/n)");
        String response = scanner.nextLine().toLowerCase();
        return response.equals("y");
    }
    private String changeUser(){
        System.out.println("Введите UUID пользователя:");
        String newUUID = scanner.nextLine();
        if(isUUIDValid(newUUID)){
            saveUUIDToLocalStore(newUUID);
            logger.info("Авторизация пользователя с UUID: " + newUUID);
            return newUUID;

        } else{
            logger.info("UUID недействителен");
            return loadOrGenerateUUID();
        }
    }

    private String generateAndSaveUUID() {
        String newUUID = UUID.randomUUID().toString();
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
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
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement preparedStatement = connection.prepareStatement("SELECT COUNT(*) FROM users WHERE uuid = ?")) {
            preparedStatement.setString(1, uuidToCheck);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getInt(1) > 0;
            }
        } catch (SQLException e) {
            logger.error("Ошибка при проверке UUID в базе данных: " + e.getMessage(), e);
            return false;
        }
        return false;
    }


    private String loadUUIDFromLocalStore() {
        String pathToFile = Paths.get(getUserDataFolder(), "config.txt").toString();
        File file = new File(pathToFile);
        if(file.exists()) {
            try (Scanner scanner = new Scanner(file)){
                return scanner.nextLine();
            } catch (IOException e) {
                logger.error("Ошибка при чтении файла UUID: " + e.getMessage(), e);
            }
        }
        return null;
    }

    private void saveUUIDToLocalStore(String uuid) {
        String pathToFile = Paths.get(getUserDataFolder(), "config.txt").toString();

        try (FileWriter fileWriter = new FileWriter(pathToFile)){
            fileWriter.write(uuid);
        } catch (IOException e) {
            logger.error("Ошибка при сохранении UUID в файл: " + e.getMessage(), e);
        }
    }
    private static String getUserDataFolder(){
        String userHome = System.getProperty("user.home");
        Path dataFolder = Paths.get(userHome, ".short_links_app");
        File folder = dataFolder.toFile();

        if(!folder.exists()) {
            folder.mkdirs();
        }
        return dataFolder.toString();
    }

    private void createDatabaseIfNotExists() {
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            if (connection != null) {
                String createUsersTableSQL = "CREATE TABLE IF NOT EXISTS users (uuid VARCHAR(255) PRIMARY KEY)";
                String createLinksTableSQL = "CREATE TABLE IF NOT EXISTS short_urls (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY," +
                        "uuid VARCHAR(255)," +
                        "short_url TEXT UNIQUE," +
                        "original_url TEXT," +
                        "expiration_time TIMESTAMP," +
                        "max_clicks INTEGER," +
                        "clicks INTEGER DEFAULT 0)";
                try(Statement statement = connection.createStatement()){
                    statement.execute(createUsersTableSQL);
                    statement.execute(createLinksTableSQL);
                }
            }
        } catch (SQLException e) {
            logger.error("Ошибка при создании базы данных: " + e.getMessage(), e);
        }
    }

    public String getCurrentUserUUID() {
        return uuid;
    }
}