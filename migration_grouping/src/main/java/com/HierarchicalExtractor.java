package com;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
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

public class HierarchicalExtractor {

    private static final String HOST = "https://localhost";
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";
    private static final String TARGET_FOLDER = System.getProperty("user.home") + "/Downloads/alfresco_tree_migration";

    public static void main(String[] args) {
        try {
            System.out.println("=== STARTING HIERARCHICAL EXTRACTION ===");
            
            HttpClient client = createSecureHttpClient();
            String authHeader = "Basic " + Base64.getEncoder().encodeToString((USERNAME + ":" + PASSWORD).getBytes());

            String searchUrl = HOST + "/alfresco/api/-default-/public/search/versions/1/search";
            
            // Note the addition of the "include" array to fetch the path
            String jsonSearchBody = """
                {
                  "query": {
                    "query": "+(TYPE:'cm:content' OR TYPE:'cm:folder') AND +cm:creator:'admin'",
                    "language": "afts"
                  },
                  "include": ["path"]
                }
                """;

            HttpRequest searchRequest = HttpRequest.newBuilder()
                    .uri(URI.create(searchUrl))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonSearchBody))
                    .build();

            System.out.println("Searching for files and folders in Alfresco...");
            HttpResponse<String> searchResponse = client.send(searchRequest, HttpResponse.BodyHandlers.ofString());

            if (searchResponse.statusCode() != 200) {
                System.err.println("Search error. Code: " + searchResponse.statusCode());
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(searchResponse.body());
            JsonNode entries = rootNode.path("list").path("entries");

            System.out.println("Found " + entries.size() + " items matching the metadata.");

            for (JsonNode entryNode : entries) {
                JsonNode entry = entryNode.path("entry");
                String id = entry.path("id").asText();
                String itemName = entry.path("name").asText();
                boolean isFolder = entry.path("isFolder").asBoolean();
                
                // Extract the hierarchical path from Alfresco
                String alfrescoPath = entry.path("path").path("name").asText();
                
                // Remove the leading slash so it doesn't break local path resolution
                if (alfrescoPath.startsWith("/")) {
                    alfrescoPath = alfrescoPath.substring(1);
                }

                // Build the local directory path mapping the Alfresco structure
                Path localDirectory = Paths.get(TARGET_FOLDER, alfrescoPath);
                
                if (isFolder) {
                    System.out.println("-> [FOLDER] Creating tree: " + alfrescoPath + "/" + itemName);
                    Path newFolder = localDirectory.resolve(itemName);
                    if (!Files.exists(newFolder)) {
                        Files.createDirectories(newFolder);
                    }
                } else {
                    System.out.println("-> [FILE] Downloading to: " + alfrescoPath + "/" + itemName);
                    // Ensure the parent directory exists before downloading the file
                    if (!Files.exists(localDirectory)) {
                        Files.createDirectories(localDirectory);
                    }
                    Path finalFilePath = localDirectory.resolve(itemName);
                    downloadFile(client, authHeader, id, finalFilePath);
                }
            }

            System.out.println("\n=== PROCESS FINISHED ===");
            System.out.println("Check your fully recreated tree at: " + TARGET_FOLDER);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Downloads the physical file and saves it to the specific hierarchical path
     */
    private static void downloadFile(HttpClient client, String authHeader, String id, Path finalFilePath) {
        try {
            String downloadUrl = HOST + "/alfresco/api/-default-/public/alfresco/versions/1/nodes/" + id + "/content";
            
            HttpRequest downloadRequest = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .header("Authorization", authHeader)
                    .GET()
                    .build();

            HttpResponse<InputStream> response = client.send(downloadRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 200) {
                try (InputStream is = response.body()) {
                    Files.copy(is, finalFilePath, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                System.err.println("   Could not download " + finalFilePath.getFileName() + ". Code: " + response.statusCode());
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
