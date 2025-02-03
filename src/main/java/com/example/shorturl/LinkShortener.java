package org.example.shorturl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.awt.Desktop;
import java.net.URI;
import java.net.URISyntaxException;

public class LinkShortener {
    private static final String YANDEX_API_URL = "https://clck.ru/--";
    private static final String DB_URL = "jdbc:sqlite:" + Paths.get(org.example.shorturl.UserManager.getUserDataFolder(), "app_data.db").toString();
    private static final Logger logger = LoggerFactory.getLogger(LinkShortener.class);
    private final Scanner scanner = new Scanner(System.in);
    private final org.example.shorturl.UserManager userManager;

    public LinkShortener(org.example.shorturl.UserManager userManager) {
        this.userManager = userManager;

        if (userManager.getCurrentUserUUID() == null) {
            logger.error("UUID пользователя не найден. Программа не может продолжить работу.");
            throw new IllegalStateException("UUID пользователя не найден.");
        }

        cleanUpExpiredLinks();
        showMenu();
    }
    private String generateShortUrl(String originalUrl) {
        try {
            URL url = new URL(YANDEX_API_URL + "?url=" + URLEncoder.encode(originalUrl, "UTF-8"));
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String inputLine;

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            return response.toString();
        } catch (IOException e) {
            logger.error("Ошибка при генерации короткой ссылки: " + e.getMessage(), e);
            return null;
        }
    }

    private void showMenu() {
        String userUUID = userManager.getCurrentUserUUID();
        while (true) {
            System.out.println("\nДействия:");
            System.out.println("1. Показать все сокращённые ссылки");
            System.out.println("2. Перейти по существующей ссылке");
            System.out.println("3. Изменить лимит переходов или время жизни ссылки");
            System.out.println("4. Сократить новую ссылку");
            System.out.println("5. Выйти");
            System.out.print("Выберите действие: ");

            String choice = scanner.nextLine();

            switch (choice) {
                case "1":
                    showAllLinks(userUUID);
                    break;
                case "2":
                    openExistingLink(userUUID);
                    break;
                case "3":
                    changeLinkSettings(userUUID);
                    break;
                case "4":
                    shortenNewLink(userUUID);
                    break;
                case "5":
                    cleanUpExpiredLinks();
                    return; // выход из программы
                default:
                    System.out.println("Неверный выбор. Попробуйте снова.");
            }
        }
    }

    private void changeLinkSettings(String userUUID) {
        System.out.print("Введите короткую ссылку для изменения: ");
        String shortUrl = scanner.nextLine();
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM short_urls WHERE short_url = ? AND uuid = ?")) {
            preparedStatement.setString(1, shortUrl);
            preparedStatement.setString(2, userUUID);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                System.out.println("Выберите что изменить:");
                System.out.println("1. Лимит переходов");
                System.out.println("2. Время жизни");
                String response = scanner.nextLine();
                switch (response) {
                    case "1":
                        updateMaxClicks(shortUrl, userUUID);
                        break;
                    case "2":
                        updateExpirationTime(shortUrl, userUUID);
                        break;
                    default:
                        System.out.println("Неверный выбор.");
                }
            } else {
                System.out.println("Короткая ссылка не найдена.");
            }
        } catch (SQLException e) {
            logger.error("Ошибка при изменении настроек ссылки: " + e.getMessage(), e);
        }
    }
    private void updateMaxClicks(String shortUrl, String userUUID) {
        System.out.println("Введите новый лимит переходов:");
        try {
            int maxClicks = Integer.parseInt(scanner.nextLine());
            try (Connection connection = DriverManager.getConnection(DB_URL);
                 PreparedStatement preparedStatement = connection.prepareStatement("UPDATE short_urls SET max_clicks = ? WHERE short_url = ? AND uuid = ?")) {
                preparedStatement.setInt(1, maxClicks);
                preparedStatement.setString(2, shortUrl);
                preparedStatement.setString(3, userUUID);
                preparedStatement.executeUpdate();
                logger.info("Лимит переходов обновлен");
            }
        } catch (NumberFormatException e){
            System.out.println("Неверный формат числа");
        } catch (SQLException e) {
            logger.error("Ошибка при изменении лимита: " + e.getMessage(), e);
            e.printStackTrace();
        }
    }
    private void updateExpirationTime(String shortUrl, String userUUID){
        System.out.println("Введите новое время жизни в часах:");
        try {
            int lifetimeInHours = Integer.parseInt(scanner.nextLine());
            try (Connection connection = DriverManager.getConnection(DB_URL);
                 PreparedStatement preparedStatement = connection.prepareStatement("UPDATE short_urls SET expiration_time = ? WHERE short_url = ? AND uuid = ?")) {
                LocalDateTime expirationTime = LocalDateTime.now().plusHours(lifetimeInHours);
                preparedStatement.setTimestamp(1, Timestamp.valueOf(expirationTime));
                preparedStatement.setString(2, shortUrl);
                preparedStatement.setString(3, userUUID);
                preparedStatement.executeUpdate();
                logger.info("Время жизни обновлено");
            }
        } catch (NumberFormatException e){
            System.out.println("Неверный формат числа");
        }
        catch (SQLException e) {
            logger.error("Ошибка при изменении времени: " + e.getMessage(), e);
            e.printStackTrace();
        }
    }
    private String shortenNewLink(String userUUID) {
        System.out.println("Введите длинную ссылку для сокращения:");
        String originalUrl = scanner.nextLine();
        System.out.println("Введите лимит переходов (оставьте пустым для значения по умолчанию 1):");
        String maxClicksStr = scanner.nextLine();
        System.out.println("Введите время жизни в часах (оставьте пустым для значения по умолчанию 24):");
        String lifetimeStr = scanner.nextLine();
        int maxClicks = maxClicksStr.isEmpty() ? 1 : Integer.parseInt(maxClicksStr);
        int lifetimeInHours = lifetimeStr.isEmpty() ? 24 : Integer.parseInt(lifetimeStr);
        String shortUrl = generateShortUrl(originalUrl);
        if(shortUrl == null){
            return null;
        }
        while (isShortUrlExists(shortUrl, userUUID)) {
            System.out.println("Данная короткая ссылка уже существует в базе, запрашиваем новую");
            shortUrl = generateShortUrl(originalUrl);
            if (shortUrl == null) {
                return null;
            }
        }
        LocalDateTime expirationTime = LocalDateTime.now().plusHours(lifetimeInHours);
        saveShortUrl(userUUID, shortUrl, originalUrl, expirationTime, maxClicks);
        logger.info("Короткая ссылка: " + shortUrl);
        return shortUrl;
    }
    private void showAllLinks(String userUUID) {
        try(Connection connection = DriverManager.getConnection(DB_URL);
            PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM short_urls WHERE uuid = ?")) {
            preparedStatement.setString(1, userUUID);
            ResultSet resultSet = preparedStatement.executeQuery();
            List<ShortUrlData> links = new ArrayList<>();
            int counter = 1;
            while(resultSet.next()){
                LocalDateTime expirationTime = resultSet.getTimestamp("expiration_time").toLocalDateTime();
                links.add(new ShortUrlData(counter, resultSet.getString("short_url"), resultSet.getString("original_url"), expirationTime, resultSet.getInt("max_clicks"), resultSet.getInt("clicks")));
                counter++;
            }
            if(links.isEmpty()){
                System.out.println("Нет сокращенных ссылок для текущего пользователя.");
            } else {
                for(ShortUrlData link: links){
                    System.out.println(link);
                }
                System.out.println("Если хотите перейти по какой-либо ссылке, введите ее номер, иначе нажмите любую клавишу:");
                String response = scanner.nextLine();
                try {
                    int number = Integer.parseInt(response);
                    if(number > 0 && number <= links.size()){
                        openLinkInBrowser(links.get(number - 1).shortUrl);
                    }
                }
                catch (NumberFormatException e){
                    // do nothing
                }
            }
        } catch (SQLException e) {
            logger.error("Ошибка при отображении списка ссылок: " + e.getMessage(), e);
            e.printStackTrace();
        }
    }
    private void openExistingLink(String userUUID){
        System.out.println("Введите короткую ссылку:");
        String shortUrl = scanner.nextLine();
        openLinkInBrowser(shortUrl);
    }
    private boolean isShortUrlExists(String shortUrl, String userUUID) {
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement preparedStatement = connection.prepareStatement("SELECT COUNT(*) FROM short_urls WHERE short_url = ? AND uuid = ?")) {
            preparedStatement.setString(1, shortUrl);
            preparedStatement.setString(2, userUUID);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getInt(1) > 0;
            }
        } catch (SQLException e) {
            logger.error("Ошибка при проверке short_url в базе данных: " + e.getMessage(), e);
            e.printStackTrace();
            return false;
        }
        return false;
    }
    private void openLinkInBrowser(String shortUrl) {
        try(Connection connection = DriverManager.getConnection(DB_URL);
            PreparedStatement preparedStatement = connection.prepareStatement(
                    "SELECT original_url, expiration_time, max_clicks, clicks, uuid FROM short_urls WHERE short_url = ?")) {
            preparedStatement.setString(1, shortUrl);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                String uuid = resultSet.getString("uuid");
                if(!Objects.equals(uuid, userManager.getCurrentUserUUID())){
                    logger.info("Это ссылка не принадлежит текущему пользователю");
                    return;
                }
                LocalDateTime expirationTime = resultSet.getTimestamp("expiration_time").toLocalDateTime();
                int maxClicks = resultSet.getInt("max_clicks");
                int clicks = resultSet.getInt("clicks");
                if (LocalDateTime.now().isAfter(expirationTime)) {
                    deleteLink(shortUrl, uuid);
                    System.out.println("Время жизни ссылки истекло");
                    return;
                }
                if (clicks >= maxClicks) {
                    deleteLink(shortUrl, uuid);
                    System.out.println("Лимит переходов по ссылке истёк");
                    return;
                }
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(shortUrl));
                    updateClicks(shortUrl, clicks + 1, uuid);
                    logger.info("Перенаправление на: " + shortUrl);
                }
            } else {
                System.out.println("Ссылка не найдена");
            }
        } catch (SQLException | IOException | URISyntaxException e) {
            logger.error("Ошибка при переходе по ссылке: " + e.getMessage(), e);
            e.printStackTrace();
        }
    }
    private void updateClicks(String shortUrl, int clicks, String uuid){
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement preparedStatement = connection.prepareStatement("UPDATE short_urls SET clicks = ? WHERE short_url = ? AND uuid = ?")) {
            preparedStatement.setInt(1, clicks);
            preparedStatement.setString(2, shortUrl);
            preparedStatement.setString(3, uuid);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            logger.error("Ошибка при изменении количества кликов: " + e.getMessage(), e);
            e.printStackTrace();
        }
    }
    private void deleteLink(String shortUrl, String uuid){
        try(Connection connection = DriverManager.getConnection(DB_URL);
            PreparedStatement preparedStatement = connection.prepareStatement("DELETE FROM short_urls WHERE short_url = ? AND uuid = ?")){
            preparedStatement.setString(1, shortUrl);
            preparedStatement.setString(2, uuid);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            logger.error("Ошибка при удалении ссылки: " + e.getMessage(), e);
            e.printStackTrace();
        }
    }
    private void cleanUpExpiredLinks() {
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement preparedStatement = connection.prepareStatement("DELETE FROM short_urls WHERE expiration_time < ? OR clicks >= max_clicks")) {
            preparedStatement.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            int deletedRows = preparedStatement.executeUpdate();
            logger.info("Удалено просроченных или исчерпавших лимит ссылок: " + deletedRows);
        } catch (SQLException e) {
            logger.error("Ошибка при очистке просроченных или исчерпавших лимит ссылок: " + e.getMessage(), e);
        }
    }

    private void saveShortUrl(String userUUID, String shortUrl, String originalUrl, LocalDateTime expirationTime, int maxClicks) {
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO short_urls (uuid, short_url, original_url, expiration_time, max_clicks) VALUES (?, ?, ?, ?, ?)")) {
            preparedStatement.setString(1, userUUID);
            preparedStatement.setString(2, shortUrl);
            preparedStatement.setString(3, originalUrl);
            preparedStatement.setTimestamp(4, Timestamp.valueOf(expirationTime));
            preparedStatement.setInt(5, maxClicks);
            preparedStatement.executeUpdate();
            logger.info("Ссылка успешно сокращена и сохранена.");
        } catch (SQLException e) {
            logger.error("Ошибка при сохранении короткой ссылки: " + e.getMessage(), e);
        }
    }
    private static class ShortUrlData {
        int id;
        String shortUrl;
        String originalUrl;
        LocalDateTime expirationTime;
        int maxClicks;
        int clicks;
        public ShortUrlData(int id, String shortUrl, String originalUrl, LocalDateTime expirationTime, int maxClicks, int clicks){
            this.id = id;
            this.shortUrl = shortUrl;
            this.originalUrl = originalUrl;
            this.expirationTime = expirationTime;
            this.maxClicks = maxClicks;
            this.clicks = clicks;
        }
        @Override
        public String toString(){
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return String.format("%d. Короткая ссылка: %s, Оригинальная ссылка: %s, Время истечения: %s,  Лимит переходов: %d,  Текущие переходы: %d",
                    id, shortUrl, originalUrl, expirationTime.format(formatter), maxClicks, clicks);
        }
    }
}