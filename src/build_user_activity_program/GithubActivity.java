package build_user_activity_program;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GithubActivity {
    public static void main(String[] args) {

        if (args.length == 0) {
            System.out.println("Usage: java GithubActivity <username>");
            return;
        }


        String username = args[0];
        String json = fetchUserActivity(username);

        if (json == null) {
            return;
        }

        if (json.isBlank() || json.equals("[]")) {
            System.out.println("No recent activity found.");
            return;
        }

        List<String> events = splitTopLevelArray(json);

        if (events.isEmpty()) {
            System.out.println("No events found.");
            return;
        }

        Map<String, Map<String, Integer>> counts =
                countActivities(events);

        for (Map.Entry<String, Map<String, Integer>> repoEntry
                : counts.entrySet()) {

            String repoName = repoEntry.getKey();

            for (Map.Entry<String, Integer> eventEntry
                    : repoEntry.getValue().entrySet()) {

                System.out.println(
                        formatCountedEvent(
                                eventEntry.getKey(),
                                repoName,
                                eventEntry.getValue()
                        )
                );
            }
        }
    }

    private static String fetchUserActivity(String username) {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        "https://api.github.com/users/" + username + "/events"
                ))
                .header("Accept", "application/vnd.github+json")
                .build();

        try {
            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            int statusCode = response.statusCode();

            if (statusCode == 200) {
                return response.body();
            }
            if (statusCode == 404) {
                System.out.println("Error: GitHub user not found.");
            } else if (statusCode == 403) {
                System.out.println(
                        "Error: Access forbidden. You may have reached GitHub's API rate limit.");
            } else {
                System.out.println("Error: GitHub API returned HTTP status " + statusCode);
            }

            return null;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Error: The request was interrupted.");
            return null;
        } catch (java.io.IOException e) {
            System.out.println("Error: Could not connect to GitHub.");
            return null;
        }
    }


    public static String formatCountedEvent(
            String type, String repoName, int count) {

        switch (type) {
            case "PushEvent":
                return "- Pushed " + count + " " + (count == 1 ? "commit" : "commits")
                        + " to " + repoName;

            case "CreateEvent":
                return "- Created " + count + " " + (count == 1 ? "branch or repository" : "branches or repositories")
                        + " in " + repoName;

            case "IssuesEvent":
                return "- " + count + " " + (count == 1 ? "issue event" : "issue events")
                        + " in " + repoName;

            case "WatchEvent":
                return "- Starred " + repoName;

            case "ForkEvent":
                return "- Forked " + repoName;

            case "PullRequestEvent":
                return "- Had " + count + " " + (count == 1 ? "pull request event" : "pull request events")
                        + " in " + repoName;

            default:
                return "- Performed " + type + " " + count + (count == 1 ? " time" : " times")
                        + " in " + repoName;
        }
    }

    public static Map<String, Map<String, Integer>> countActivities(
            List<String> events) {

        Map<String, Map<String, Integer>> counts = new HashMap<>();

        for (String event : events) {
            String typeRaw = getProperty(event, "type");
            String repoRaw = getProperty(event, "repo");

            if (typeRaw == null || repoRaw == null) {
                continue;
            }

            String repoNameRaw = getProperty(repoRaw, "name");

            if (repoNameRaw == null) {
                continue;
            }

            String type = (String) convertValue(typeRaw);
            String repoName = (String) convertValue(repoNameRaw);

            int amount = 1;

            // PushEvent: count commits, not push events
            if ("PushEvent".equals(type)) {
                String payload = getProperty(event, "payload");

                if (payload == null) {
                    continue;
                }

                String sizeRaw = getProperty(payload, "size");

                if (sizeRaw == null) {
                    continue;
                }

                amount = ((Number) convertValue(sizeRaw)).intValue();
            }

            counts.putIfAbsent(repoName, new HashMap<>());

            Map<String, Integer> repoCounts = counts.get(repoName);

            repoCounts.put(
                    type,
                    repoCounts.getOrDefault(type, 0) + amount
            );
        }

        return counts;
    }


    private static List<Integer> findTopLevelCommas(String json) {
        List<Integer> commaList = new ArrayList<>();

        int depth = 1;
        boolean escaped = false;
        boolean insideString = false;

        for (int i=1 ; i<json.length() ; i++) {
            char c = json.charAt(i);

            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            }
            else if (c == '"') {
                insideString = !insideString;
            } else if (!insideString) {
                if (c == '{' || c == '[') {
                    depth++;
                } else if(c == '}' || c == ']') {
                    depth--;
                } else if (c == ',' && depth == 1) {
                    commaList.add(i);
                }
            }
        }

        return commaList;
    }

    public static int findKeyValueColon(String property) {

        boolean escaped = false;
        boolean insideString = false;

        for (int i=0 ; i<property.length() ; i++) {
            char c = property.charAt(i);

            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            }
            else if (c == '"') {
                insideString = !insideString;
            } else if (!insideString && c == ':') {
                return i;
            }
        }
        return -1;
    }

    public static Object convertValue(String value) {
        value = value.trim();

        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        } else if (value.equals("true") || value.equals("false")) {
            return Boolean.parseBoolean(value);
        } else if (value.equals("null")) {
            return null;
        } else if (value.startsWith("{") || value.startsWith("[")) {
            return value;
        } else {
            return Double.parseDouble(value);
        }
    }

    public static List<String> splitTopLevelArray(String json) {
        List<String> items = new ArrayList<>();

        boolean insideString = false;
        boolean escaped = false;
        int depth = 0;
        int start = -1;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escaped) {
                escaped = false;
            } else if (insideString && c == '\\') {
                escaped = true;
            } else if (c == '"') {
                insideString = !insideString;
            } else if (!insideString) {
                if (c == '{' || c == '[') {
                    if (depth == 1 && c == '{') {
                        start = i;
                    }
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;

                    if (depth == 1 && start != -1 && c == '}') {
                        items.add(json.substring(start, i + 1));
                        start = -1;
                    }
                }
            }
        }

        return items;
    }

    public static String getProperty(String json, String targetKey) {
        List<Integer> commas = findTopLevelCommas(json);
        int start = 1;

        for (int comma : commas) {
            String property = json.substring(start, comma).trim();
            int colon = findKeyValueColon(property);


            String key = property.substring(0, colon).trim();

            if (key.equals("\"" + targetKey + "\"")) {
                return property.substring(colon + 1).trim();
            }

            start = comma + 1;
        }

        String property = json.substring(start, json.length() - 1).trim();
        int colon = findKeyValueColon(property);

        String key = property.substring(0, colon).trim();

        if (key.equals("\"" + targetKey + "\"")) {
            return property.substring(colon + 1).trim();
        }

        return null;
    }

}
