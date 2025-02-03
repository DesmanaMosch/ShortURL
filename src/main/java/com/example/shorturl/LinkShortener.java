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
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LinkShortener {
    private static final String YANDEX_API_URL = "https://clck.ru/--";
    private static final String DB_URL = "jdbc:sqlite:" + Paths.get(org.example.shorturl.UserManager.getUserDataFolder(), "app_data.db").toString();
    private static final Logger logger = LoggerFactory.getLogger(LinkShortener.class);
    private final Scanner scanner = new Scanner(System.in);
    private final org.example.shorturl.UserManager userManager;
    private static final Pattern URL_PATTERN = Pattern.compile("^(https?|ftp)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]");

    public LinkShortener(org.example.shorturl.UserManager userManager) {
        this.userManager = userManager;

        if (userManager.getCurrentUserUUID() == null) {
            logger.error("UUID пользователя не найден. Программа не может продолжить работу.");
            throw new IllegalStateException("UUID пользователя не найден.");
        }
        showMenu();
    }

    private String generateShortUrl(String originalUrl) {
        try {
            URL url = new URL(YANDEX_API_URL + "?url=" + URLEncoder.encode(originalUrl, "UTF-8"));
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            int responseCode = connection.getResponseCode();

            if(responseCode == HttpURLConnection.HTTP_OK){
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String inputLine;

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                return response.toString();
            } else {
                logger.error("Ошибка при запросе к API Яндекса. Код ответа: " + responseCode);
                System.out.println("Ошибка при запросе к API Яндекса. Повторите запрос позднее.");
                return null;
            }

        } catch (IOException e) {
            logger.error("Ошибка при генерации короткой ссылки: " + e.getMessage(), e);
            System.out.println("Ошибка при генерации короткой ссылки. Повторите запрос позднее.");
            return null;
        }
    }

    private void showMenu() {
        while (true) {
            System.out.println("\nДействия:");
            System.out.println("1. Показать все сокращённые ссылки");
            System.out.println("2. Перейти по существующей ссылке");
            System.out.println("3. Изменить лимит переходов или время жизни ссылки");
            System.out.println("4. Сократить новую ссылку");
            System.out.println("5. Выйти");
            System.out.print("Выберите действие: ");
            String choice = scanner.nextLine();
            String userUUID = userManager.getCurrentUserUUID();
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
                    shortenLink(userUUID);
                    break;
                case "5":
                    System.out.println("Выход из программы.");
                    return;
                default:
                    System.out.println("Некорректный ввод. Пожалуйста, выберите действие из списка.");
            }
        }
    }

    private void changeLinkSettings(String userUUID) {
        System.out.print("Введите короткую ссылку для изменения: ");
        String shortUrl = scanner.nextLine().trim();

        if (shortUrl.isEmpty()) {
            System.out.println("Вы не ввели короткую ссылку. Попробуйте ещё раз.");
            return;
        }
        ShortUrlData shortUrlData = getShortUrlData(shortUrl, userUUID);

        if(shortUrlData != null){
            System.out.println("Выберите что изменить:");
            System.out.println("1. Лимит переходов");
            System.out.println("2. Время жизни");
            String response = scanner.nextLine().trim();
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
    }


    private ShortUrlData getShortUrlData(String shortUrl, String userUUID){
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM short_urls WHERE short_url = ? AND uuid = ?")) {
            preparedStatement.setString(1, shortUrl);
            preparedStatement.setString(2, userUUID);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                LocalDateTime expirationTime = resultSet.getTimestamp("expiration_time").toLocalDateTime();
                return new ShortUrlData(0, resultSet.getString("short_url"), resultSet.getString("original_url"), expirationTime, resultSet.getInt("max_clicks"), resultSet.getInt("clicks"));
            }
        } catch (SQLException e) {
            logger.error("Ошибка при изменении настроек ссылки: " + e.getMessage(), e);
            System.out.println("Ошибка при получении данных о ссылке.");
        }
        return null;
    }

    private void updateMaxClicks(String shortUrl, String userUUID) {
        System.out.println("Введите новый лимит переходов:");
        String maxClicksStr = scanner.nextLine();
        try {
            if (maxClicksStr.isEmpty()) {
                System.out.println("Лимит переходов не может быть пустым.");
                return;
            }
            int maxClicks = Integer.parseInt(maxClicksStr);
            if (maxClicks <= 0) {
                System.out.println("Лимит переходов должен быть положительным числом.");
                return;
            }
            try (Connection connection = DriverManager.getConnection(DB_URL);
                 PreparedStatement preparedStatement = connection.prepareStatement("UPDATE short_urls SET max_clicks = ? WHERE short_url = ? AND uuid = ?")) {
                preparedStatement.setInt(1, maxClicks);
                preparedStatement.setString(2, shortUrl);
                preparedStatement.setString(3, userUUID);
                preparedStatement.executeUpdate();
                logger.info("Лимит переходов обновлен");
            }
        } catch (NumberFormatException e) {
            System.out.println("Неверный формат числа");
        } catch (SQLException e) {
            logger.error("Ошибка при изменении лимита: " + e.getMessage(), e);
            System.out.println("Ошибка при изменении лимита переходов. Попробуйте позже.");
        }
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void updateExpirationTime(String shortUrl, String userUUID) {
        System.out.println("Введите новое время жизни в часах:");
        String lifetimeStr = scanner.nextLine();
        try {
            if (lifetimeStr.isEmpty()) {
                System.out.println("Время жизни не может быть пустым.");
                return;
            }
            int lifetimeInHours = Integer.parseInt(lifetimeStr);
            if (lifetimeInHours <= 0) {
                System.out.println("Время жизни должно быть положительным числом.");
                return;
            }
            try (Connection connection = DriverManager.getConnection(DB_URL);
                 PreparedStatement preparedStatement = connection.prepareStatement("UPDATE short_urls SET expiration_time = ? WHERE short_url = ? AND uuid = ?")) {
                LocalDateTime expirationTime = LocalDateTime.now().plusHours(lifetimeInHours);
                preparedStatement.setTimestamp(1, Timestamp.valueOf(expirationTime));
                preparedStatement.setString(2, shortUrl);
                preparedStatement.setString(3, userUUID);
                preparedStatement.executeUpdate();
                logger.info("Время жизни обновлено");
            } catch (SQLException e) {
                logger.error("Ошибка при изменении времени: " + e.getMessage(), e);
                System.out.println("Ошибка при изменении времени жизни ссылки. Попробуйте позже.");
            }
        } catch (NumberFormatException e) {
            System.out.println("Неверный формат числа");
        }
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    private void shortenLink(String userUUID) {
        System.out.println("Введите длинную ссылку для сокращения:");
        String originalUrl = scanner.nextLine().trim();

        if (!isValidUrl(originalUrl)) {
            System.out.println("Некорректный формат URL. Попробуйте еще раз.");
            return;
        }
        String shortUrl = shortenNewLink(userUUID, originalUrl);
        if (shortUrl != null) {
            System.out.println("Короткая ссылка: " + shortUrl);
        }
    }
    private boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        Matcher matcher = URL_PATTERN.matcher(url);
        return matcher.matches();
    }
    private String shortenNewLink(String userUUID, String originalUrl) {
        System.out.println("Введите лимит переходов (оставьте пустым для значения по умолчанию 1):");
        String maxClicksStr = scanner.nextLine().trim();
        System.out.println("Введите время жизни в часах (оставьте пустым для значения по умолчанию 24):");
        String lifetimeStr = scanner.nextLine().trim();
        int maxClicks = maxClicksStr.isEmpty() ? 1 : Integer.parseInt(maxClicksStr);
        int lifetimeInHours = lifetimeStr.isEmpty() ? 24 : Integer.parseInt(lifetimeStr);

        if (maxClicks <= 0) {
            System.out.println("Лимит переходов должен быть положительным числом. Установлено значение по умолчанию: 1.");
            maxClicks = 1;
        }
        if (lifetimeInHours <= 0) {
            System.out.println("Время жизни должно быть положительным числом. Установлено значение по умолчанию: 24.");
            lifetimeInHours = 24;
        }
        String shortUrl;
        String currentOriginalUrl = originalUrl;
        do {
            shortUrl = generateShortUrl(currentOriginalUrl);
            if (shortUrl == null) {
                return null;
            }
            if(isShortUrlExists(shortUrl, userUUID)){
                currentOriginalUrl = currentOriginalUrl + "&";
            }
        } while (isShortUrlExists(shortUrl, userUUID));
        LocalDateTime expirationTime = LocalDateTime.now().plusHours(lifetimeInHours);
        saveShortUrl(userUUID, shortUrl, originalUrl, expirationTime, maxClicks);
        logger.info("Короткая ссылка: " + shortUrl);
        return shortUrl;
    }

    private void showAllLinks(String userUUID) {
        List<String> expiredLinks = cleanUpExpiredLinks(userUUID);
        deleteExpiredLinks(expiredLinks, userUUID);
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM short_urls WHERE uuid = ?")) {
            preparedStatement.setString(1, userUUID);
            ResultSet resultSet = preparedStatement.executeQuery();
            List<ShortUrlData> links = new ArrayList<>();
            int counter = 1;
            while (resultSet.next()) {
                LocalDateTime expirationTime = resultSet.getTimestamp("expiration_time").toLocalDateTime();
                links.add(new ShortUrlData(counter, resultSet.getString("short_url"), resultSet.getString("original_url"), expirationTime, resultSet.getInt("max_clicks"), resultSet.getInt("clicks")));
                counter++;
            }
            if (links.isEmpty()) {
                System.out.println("Нет сокращенных ссылок для текущего пользователя.");
            } else {
                for (ShortUrlData link : links) {
                    System.out.println(link);
                }
                System.out.println("Если хотите перейти по какой-либо ссылке, введите ее номер, иначе нажмите Enter:");
                String response = scanner.nextLine();
                try {
                    int number = Integer.parseInt(response);
                    if (number > 0 && number <= links.size()) {
                        openLinkInBrowser(links.get(number - 1).shortUrl);
                    }
                } catch (NumberFormatException e) {
                    // do nothing
                }
            }
        } catch (SQLException e) {
            logger.error("Ошибка при отображении списка ссылок: " + e.getMessage(), e);
            System.out.println("Ошибка при отображении списка ссылок. Попробуйте позже.");
        }
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void openExistingLink(String userUUID){
        System.out.println("Введите короткую ссылку:");
        String shortUrl = scanner.nextLine().trim();
        if (shortUrl.isEmpty()) {
            System.out.println("Вы не ввели короткую ссылку. Попробуйте ещё раз.");
            return;
        }
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
            System.out.println("Ошибка при проверке ссылки. Попробуйте позже.");
            return false;
        }
        return false;
    }
    private void openLinkInBrowser(String shortUrl) {
        String originalUrl = null;
        String uuid = null;
        int clicks = 0;
        boolean isLinkValid = false;
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement preparedStatement = connection.prepareStatement(
                     "SELECT original_url, expiration_time, max_clicks, clicks, uuid FROM short_urls WHERE short_url = ?")) {
            preparedStatement.setString(1, shortUrl);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                originalUrl = resultSet.getString("original_url");
                uuid = resultSet.getString("uuid");
                if(!Objects.equals(uuid, userManager.getCurrentUserUUID())){
                    logger.info("Это ссылка не принадлежит текущему пользователю");
                    System.out.println("Ссылка не принадлежит текущему пользователю.");
                    return;
                }
                LocalDateTime expirationTime = resultSet.getTimestamp("expiration_time").toLocalDateTime();
                int maxClicks = resultSet.getInt("max_clicks");
                clicks = resultSet.getInt("clicks");
                if (LocalDateTime.now().isAfter(expirationTime)) {
                    deleteLink(shortUrl, uuid);
                    System.out.println("Время жизни ссылки истекла, она удалена из БД.");
                    return;
                }
                if (clicks >= maxClicks) {
                    deleteLink(shortUrl, uuid);
                    System.out.println("Лимит переходов по ссылке истёк, она удалена из БД.");
                    return;
                }
                isLinkValid = true;
            } else {
                System.out.println("Ссылка не найдена");
                return;
            }
        } catch (SQLException e) {
            logger.error("Ошибка при переходе по ссылке: " + e.getMessage(), e);
            System.out.println("Ошибка при переходе по ссылке. Попробуйте позже.");
            return;
        }
        if(isLinkValid){
            try {
                URI uri = new URI(originalUrl);
                openBrowserWithRuntime(originalUrl);
                updateClicks(shortUrl, clicks + 1, uuid);
                logger.info("Перенаправление на: " + originalUrl);
            }  catch (URISyntaxException e) {
                logger.error("Некорректный URI: " + originalUrl, e);
                System.out.println("Некорректная ссылка");
            }  catch (IOException e){
                logger.error("Ошибка при открытии ссылки: " + e.getMessage(), e);
                System.out.println("Ошибка при открытии ссылки. Попробуйте позже.");
            }
        }
    }
    private void openBrowserWithRuntime(String url) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String[] cmd = new String[0];
        if (os.contains("win")) {
            cmd = new String[]{"rundll32", "url.dll,FileProtocolHandler", url};
        } else if (os.contains("mac")) {
            cmd = new String[]{"open",  "'" + url.replace("'", "\\'") + "'"};
        } else if (os.contains("nix") || os.contains("nux")) {
            cmd = new String[]{"xdg-open", url};
        } else {
            System.out.println("Ваша операционная система не поддерживается для открытия ссылок.");
            return;
        }
        Runtime.getRuntime().exec(cmd);
        logger.info("Открытие ссылки в браузере через команду: " + String.join(" ", cmd));
    }

    private void updateClicks(String shortUrl, int newClicksCount, String uuid) {
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement preparedStatement = connection.prepareStatement(
                     "UPDATE short_urls SET clicks = ? WHERE short_url = ? AND uuid = ?")) {
            preparedStatement.setInt(1, newClicksCount);
            preparedStatement.setString(2, shortUrl);
            preparedStatement.setString(3, uuid);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            logger.error("Ошибка при изменении количества кликов: " + e.getMessage(), e);
            System.out.println("Ошибка при обновлении количества переходов. Попробуйте позже.");
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
            System.out.println("Ошибка при удалении ссылки. Попробуйте позже.");
        }
    }
    private List<String> cleanUpExpiredLinks(String userUUID) {
        List<String> expiredLinks = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement preparedStatement = connection.prepareStatement(
                     "SELECT short_url, uuid, expiration_time, max_clicks, clicks FROM short_urls WHERE uuid = ?")) {
            preparedStatement.setString(1, userUUID);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                LocalDateTime expirationTime = resultSet.getTimestamp("expiration_time").toLocalDateTime();
                String shortUrl = resultSet.getString("short_url");
                String uuid = resultSet.getString("uuid");
                int maxClicks = resultSet.getInt("max_clicks");
                int clicks = resultSet.getInt("clicks");
                if (LocalDateTime.now().isAfter(expirationTime) || clicks >= maxClicks) {
                    expiredLinks.add(shortUrl);
                }
            }
        } catch (SQLException e) {
            logger.error("Ошибка при проверке просроченных ссылок: " + e.getMessage(), e);
            System.out.println("Ошибка при проверке просроченных ссылок. Попробуйте позже.");
        }
        return expiredLinks;
    }
    private void deleteExpiredLinks(List<String> expiredLinks, String userUUID){
        for (String shortUrl: expiredLinks){
            deleteLink(shortUrl,userUUID);
        }
        logger.info("Удалено просроченных или исчерпавших лимит ссылок: " + expiredLinks.size());
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
            System.out.println("Ошибка при сохранении сокращенной ссылки. Попробуйте позже.");
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