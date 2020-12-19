package it.univaq.disim.sealab.microservicesrefactoring;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import com.google.common.collect.ImmutableList;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerCertificates;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.DockerClient.ListContainersParam;
import com.spotify.docker.client.DockerClient.ListNetworksParam;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.AttachedNetwork;
import com.spotify.docker.client.messages.Container;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.ContainerInfo;
import com.spotify.docker.client.messages.ContainerUpdate;
import com.spotify.docker.client.messages.EndpointConfig;
import com.spotify.docker.client.messages.HostConfig;
import com.spotify.docker.client.messages.Network;
import com.spotify.docker.client.messages.NetworkConnection;
import com.spotify.docker.client.messages.PortBinding;

public class Refactoring {

	final DockerClient docker;

	/**
	 * Bind to the docker local unix socket
	 */
	public Refactoring() {
		docker = new DefaultDockerClient("unix:///var/run/docker.sock");
	}

	/**
	 * Bind to a remote docker daemon.
	 * 
	 * @param uri       URI of the remote docker daemon
	 * @param certsPath path to certificates
	 * @throws DockerCertificateException
	 */
	public Refactoring(final URI uri, final Path certsPath) throws DockerCertificateException {
		docker = new DefaultDockerClient(uri, new DockerCertificates(certsPath));
	}

	/**
	 * Clones a container.
	 * 
	 * @param containerId ID of the container to be cloned
	 * @param name        The name to assign to the new container
	 * @return The ID of the new container
	 */
	public String cloneContainer(final String containerId, final String name) {

		try {

			ContainerInfo containerInfo = docker.inspectContainer(containerId);

			// Get the image of the container to clone
			final ContainerConfig config = ContainerConfig.builder().image(containerInfo.image()).build();

			// Creates the new container
			final ContainerCreation clonedContainer = docker.createContainer(config, name.replace("/", ""));
			final String newID = clonedContainer.id();

			// Attach to the same network and
			// set an alias based on the cloned container
			final AttachedNetwork net = (AttachedNetwork) containerInfo.networkSettings().networks().entrySet()
					.iterator().next().getValue();
			docker.connectToNetwork(net.networkId(),
					NetworkConnection.builder().containerId(newID).endpointConfig(
							EndpointConfig.builder().aliases(ImmutableList.of(
									net.aliases().get(1) + "_clone")
							).build()
					).build());

			// Disconnect from default network
			List<Network> netlist = docker.listNetworks(ListNetworksParam.byNetworkName("bridge"));
			docker.disconnectFromNetwork(newID, netlist.get(0).id());

			// List all containers
			/*
			System.out.println("List of all containers:");
			List<Container> containers = docker.listContainers(ListContainersParam.allContainers());
			for (Container g : containers) {
				System.out.println(g.names());
			}
			*/

			return newID;

		} catch (DockerException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (InterruptedException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		return null;
	}

	/**
	 * @param containerId      The ID of the old container
	 * @param serviceID        The application name of the microservice as appears
	 *                         on Eureka dashboard
	 * @param newContainerName The name of the new container
	 * @param functionName     The name of the RequestMapping to switch to another
	 *                         container
	 * @return The ID of the new Docker container
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public String moveFunctionToNewContainer(String containerId, String serviceID, String newContainerName,
			String functionName) {

		String newContainerID = null;
		try {

			ContainerInfo containerInfo = docker.inspectContainer(containerId);

			Map<String, List<PortBinding>> portMapping = containerInfo.networkSettings().ports();

			String port = (String) portMapping.keySet().toArray()[0];
			port = port.substring(0, port.indexOf("/"));

			newContainerID = cloneContainer(containerId, newContainerName);

			/***********************
			 * Edit Zuul configuration binded with config-server
			 **************************/

			DumperOptions options = new DumperOptions();
			options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
			Yaml yaml = new Yaml(options);

			// Get the path of zuul.yml
			java.util.Properties props = new java.util.Properties();
			java.net.URL url = this.getClass().getClassLoader().getResource("myproject.properties");
			props.load(url.openStream());
			String path = props.getProperty("project.config");

			InputStream inputStream = new FileInputStream(path);
			LinkedHashMap root = yaml.load(inputStream);

			if (!root.containsKey("zuul")) {

				LinkedHashMap zuul = new LinkedHashMap();
				LinkedHashMap routes = new LinkedHashMap();

				zuul.put("routes", routes);
				root.put("zuul", zuul);
			} else {
				LinkedHashMap zuul = (LinkedHashMap) root.get("zuul");

				if (!zuul.containsKey("routes")) {

					LinkedHashMap routes = new LinkedHashMap();
					zuul.put("routes", routes);

				}
			}

			LinkedHashMap zuul = (LinkedHashMap) root.get("zuul");
			LinkedHashMap routes = (LinkedHashMap) zuul.get("routes");

			// Redirecting function requests to the new container
			LinkedHashMap<String, String> newroute = new LinkedHashMap<String, String>();

			newroute.put("path", "/" + serviceID + "/" + functionName + "/**");
			newroute.put("url", "http://" + newContainerName + ":" + port + "/" + functionName + "/");

			((LinkedHashMap) routes).put("redirect_" + functionName + "_to_" + newContainerName, newroute);

			FileWriter output = new FileWriter(path);

			yaml.dump(root, output);
			output.flush();

			/**************************
			 * Run refresh of Zuul microservice
			 **************************/

			refreshZuulRoutes();

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (DockerException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return newContainerID;
	}

	/**
	 * @param serviceID        The name of Spring microservice that contains the
	 *                         function
	 * @param functionName     The name of the function
	 * @param otherContainerID The ID of the container on which you want to redirect
	 *                         the function. The container need to implement the
	 *                         function. It also need to be running.
	 * @return If successful, returns the ID of the container used.
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public String moveFunctionToAnotherContainer(String serviceID, String functionName, String otherContainerID) {

		try {

			ContainerInfo containerInfo = docker.inspectContainer(otherContainerID);

			Map<String, List<PortBinding>> portMapping = containerInfo.networkSettings().ports();

			String port = (String) portMapping.keySet().toArray()[0];
			port = port.substring(0, port.indexOf("/"));

			/***********************
			 * Edit Zuul configuration binded with config-server
			 **************************/

			DumperOptions options = new DumperOptions();
			options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); //
			Yaml yaml = new Yaml(options);

			java.util.Properties props = new java.util.Properties();

			java.net.URL url = this.getClass().getClassLoader().getResource("myproject.properties");

			props.load(url.openStream());

			String path = props.getProperty("project.config");

			InputStream inputStream = new FileInputStream(path);
			LinkedHashMap root = yaml.load(inputStream);

			if (!root.containsKey("zuul")) {

				LinkedHashMap zuul = new LinkedHashMap();
				LinkedHashMap routes = new LinkedHashMap();

				zuul.put("routes", routes);
				root.put("zuul", zuul);
			} else {
				LinkedHashMap zuul = (LinkedHashMap) root.get("zuul");

				if (!zuul.containsKey("routes")) {

					LinkedHashMap routes = new LinkedHashMap();
					zuul.put("routes", routes);

				}
			}

			LinkedHashMap zuul = (LinkedHashMap) root.get("zuul");
			LinkedHashMap routes = (LinkedHashMap) zuul.get("routes");

			LinkedHashMap<String, String> newroute = new LinkedHashMap<String, String>();

			newroute.put("path", "/" + serviceID + "/" + functionName + "/**");
			newroute.put("url", "http://" + containerInfo.name().substring(1) + ":" + port + "/" + functionName + "/");

			((LinkedHashMap) routes).put("redirect_" + functionName + "_to_" + containerInfo.name().substring(1),
					newroute);

			FileWriter output = new FileWriter(path);

			yaml.dump(root, output);
			output.flush();

			/**************************
			 * Run refresh of Zuul microservice
			 **************************/

			refreshZuulRoutes();

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (DockerException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return otherContainerID;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void removeContainer(String containerId) {

		try {
			ContainerInfo containerInfo = docker.inspectContainer(containerId);

			/***********************
			 * Remove unused routes from Zuul configuration if they exist
			 **************************/

			DumperOptions options = new DumperOptions();
			options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); //
			Yaml yaml = new Yaml(options);

			java.util.Properties props = new java.util.Properties();

			// Get hold of the path to the properties file
			// (Maven will make sure it's on the class path)
			java.net.URL url = this.getClass().getClassLoader().getResource("myproject.properties");

			// Load the file
			props.load(url.openStream());

			// Accessing values
			String path = props.getProperty("project.config"); // This will return the value of the ${basedir}

			InputStream inputStream = new FileInputStream(path);

			LinkedHashMap root = yaml.load(inputStream);

			if (root.containsKey("zuul")) {

				LinkedHashMap zuul = (LinkedHashMap) root.get("zuul");

				if (zuul.containsKey("routes")) {

					LinkedHashMap routes = (LinkedHashMap) zuul.get("routes");

					List<Object> redirectsKeys = new ArrayList<Object>();

					Iterator i = routes.keySet().iterator();
					while (i.hasNext()) {
						Object key = i.next();
						if (key.toString().contains(containerInfo.name().substring(1))) {
							redirectsKeys.add(key);
							System.out.println("Removing " + key.toString() + " from Zuul routes");
						}
					}

					for (Object key : redirectsKeys) {
						routes.put(key, "");
					}

					FileWriter output = new FileWriter(path);

					yaml.dump(root, output);
					output.flush();

				}
			}

			/**************************
			 * Run refresh of Zuul microservice
			 **************************/

			refreshZuulRoutes();

			/****** Remove the container ******/

			docker.stopContainer(containerId, 10);
			docker.removeContainer(containerId);

			System.out.println("\nList of all containers:");
			List<Container> containers = docker.listContainers();
			containers = docker.listContainers(ListContainersParam.allContainers());
			for (Container g : containers) {
				System.out.println(g.names());
			}

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (DockerException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	/**
	 * @param containerID The ID of the container to be updated.
	 * @param memory      Memory limit in bytes. Minimum allowed 4MB.
	 * @param cpuSetCpus  CPUs in which to allow execution (e.g., 0-3, 0,1).
	 * @param cpuShares   An integer value representing this container's relative
	 *                    CPU weight versus other containers.
	 */
	public void updateContainer(String containerID, long memory, String cpuSetCpus, long cpuShares) {

		final HostConfig newHostConfig = HostConfig.builder().memory(memory).cpusetCpus(cpuSetCpus).cpuShares(cpuShares)
				.build();

		try {

			ContainerUpdate containerUpdate = docker.updateContainer(containerID, newHostConfig);
			System.out.println(containerUpdate.toString());
			System.out.println("User docker stats to see changes");

		} catch (DockerException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	public void refreshZuulRoutes() throws IOException {

		URL url = new URL("http://localhost:9999/actuator/refresh");

		HttpURLConnection con = (HttpURLConnection) url.openConnection();

		con.setRequestMethod("POST");

		con.setRequestProperty("Content-Type", "application/json; utf-8");

		con.setRequestProperty("Accept", "application/json");

		con.setDoOutput(true);

		DataOutputStream out = new DataOutputStream(con.getOutputStream());

		byte[] input = "".getBytes("utf-8");
		out.write(input, 0, input.length);

		BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), "utf-8"));

		StringBuilder response = new StringBuilder();
		String responseLine = null;
		while ((responseLine = br.readLine()) != null) {
			response.append(responseLine.trim());
		}

		System.out.println("\nRoutes updated:\n" + response.toString());

	}
}
