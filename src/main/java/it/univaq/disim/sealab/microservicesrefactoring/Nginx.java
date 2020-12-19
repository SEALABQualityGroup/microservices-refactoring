package it.univaq.disim.sealab.microservicesrefactoring;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.DockerClient.ExecCreateParam;
import com.spotify.docker.client.DockerClient.ExecStartParameter;
import com.spotify.docker.client.exceptions.DockerException;

public class Nginx {
	
	/** Configuration file path */
	private String config;

	public Nginx(final String config) {
		this.config = config;
	}
	
	private void modifyConfig(final Function<String, String> modify) {

		StringBuilder output = new StringBuilder();

		// Read the config file
		try (BufferedReader file = new BufferedReader(new FileReader(config))) {
			String line;
			while ((line = file.readLine()) != null) {
				output.append(line + "\n");
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		// Apply the modifications
		final String modified = modify.apply(output.toString());
			
		// Write the config file
		try (FileOutputStream outFile = new FileOutputStream(config)) {
			outFile.write(modified.getBytes());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void addServerGroup(final String groupName, final List<String> servers) {
		modifyConfig(in -> in.replace("server {",
			String.format("upstream %s {%n%s  }%n%n  server {", groupName,
					servers.stream()
						.map(s -> String.format("    server %s;%n", s))
						.collect(Collectors.joining())))
		);
	}
	
	public void removeServerGroup(final String groupName, final String replacement) {
		// Remove the upstream group
		modifyConfig(in -> in.replaceFirst(String.format("(?s)%nupstream %s \\{.+?\\}%n", groupName), ""));
		
		// Replace the group in proxy_pass
		replaceProxyPass(groupName, replacement);
	}
	
	public void replaceProxyPass(final String match, final String replacement) {
		modifyConfig(in -> in.replaceAll(
				String.format("proxy_pass\\s+http://%s;", match),
				String.format("proxy_pass http://%s;", replacement)));
	}
	
	public static void reloadConfig(final DockerClient docker, final String containerId) throws
			DockerException, InterruptedException {
		docker.execStart(
				docker.execCreate(containerId, new String[] {"nginx", "-s", "reload"}, new ExecCreateParam[] {})
			.id(), new ExecStartParameter[] {});
	}
	
	public static void main(String[] args) {
		final Nginx nginx = new Nginx("/home/moebius/Desktop/nginx.conf");
		
		nginx.addServerGroup("pippe", Arrays.asList("pippa1:1234", "pippa2:1234"));
		nginx.replaceProxyPass("ts-admin-route-service:16113", "pippe");
		
		nginx.removeServerGroup("pippe", "ts-admin-route-service:16113");
	}
}