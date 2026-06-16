package com.example;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SelectiveExtractor {

    private static final String HOST = "https://localhost";
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";
    private static final String TARGET_FOLDER = System.getProperty("user.home") + "/Downloads/metadata_migration";

    public static void main(String[] args) {
        try {
            System.out.println("=== STARTING METADATA EXTRACTION ===");
            
            // 1. Configure the secure HTTP client (no SSL validation for localhost)
            HttpClient client = createSecureHttpClient();
            String authHeader = "Basic " + Base64.getEncoder().encodeToString((USERNAME + ":" + PASSWORD).getBytes());

            // 2. Define the metadata search (Files created by 'admin')
            // Kasu errealean, query-a aldatu metadatu desberdin batekiko bilatzeko
            String searchUrl = HOST + "/alfresco/api/-default-/public/search/versions/1/search";
            String jsonSearchBody = """
                {
                  "query": {
                    "query": "+TYPE:'cm:content' AND +cm:creator:'admin'",
                    "language": "afts"
                  }
                }
                """;

            HttpRequest searchRequest = HttpRequest.newBuilder()
                    .uri(URI.create(searchUrl))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonSearchBody))
                    .build();

            System.out.println("Searching for files in Alfresco...");
            HttpResponse<String> searchResponse = client.send(searchRequest, HttpResponse.BodyHandlers.ofString());

            if (searchResponse.statusCode() != 200) {
                System.err.println("Search error. Code: " + searchResponse.statusCode());
                return;
            }

            // 3. Process search results with Jackson
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(searchResponse.body());
            JsonNode entries = rootNode.path("list").path("entries");

            System.out.println("Found " + entries.size() + " files matching the metadata.");

            // 4. Magic loop: Iterate through the results and download them one by one
            for (JsonNode entryNode : entries) {
                JsonNode entry = entryNode.path("entry");
                String id = entry.path("id").asText();
                String fileName = entry.path("name").asText();

                System.out.println("-> Downloading: " + fileName + " (ID: " + id + ")");
                downloadFile(client, authHeader, id, fileName);
            }

            System.out.println("\n=== PROCESS FINISHED ===");
            System.out.println("Check the folder: " + TARGET_FOLDER);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Downloads the physical file using its unique ID
     */
    private static void downloadFile(HttpClient client, String authHeader, String id, String fileName) {
        try {
            String downloadUrl = HOST + "/alfresco/api/-default-/public/alfresco/versions/1/nodes/" + id + "/content";
            
            HttpRequest downloadRequest = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .header("Authorization", authHeader)
                    .GET()
                    .build();

            HttpResponse<InputStream> response = client.send(downloadRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 200) {
                Path folder = Paths.get(TARGET_FOLDER);
                if (!Files.exists(folder)) {
                    Files.createDirectories(folder);
                }
                
                Path destination = folder.resolve(fileName);
                try (InputStream is = response.body()) {
                    Files.copy(is, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                System.err.println("   Could not download " + fileName + ". Code: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("   Error downloading node " + id + ": " + e.getMessage());
        }
    }

    /**
     * Generates an HttpClient that bypasses localhost SSL restrictions
     */
    private static HttpClient createSecureHttpClient() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new SecureRandom());
        return HttpClient.newBuilder().sslContext(sslContext).build();
    }
}