import com.google.common.collect.Multimap;
import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.LoadBalancerIngress;
import io.fabric8.kubernetes.api.model.LoadBalancerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.ws.Endpoint;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;


public class KubernetesServiceDiscovery extends ServiceDiscovery {
    /*
    *----------------------------------------------------------------
    * For service type "A", the url ip retrieved will also be
    * of IP type "A"
    *
    * eg. service type - NodePort
    *     IP type      - Host Ip of a pod
    *
    *     Will not show Nodeport type IPs of LoadBalancer services or ExternalName services either
    *-----------------------------------------------------------------
    * Same service name can repeat with mulitiple service ports
    * due to having different protocols for differents ports
    * Therefore using ArrayListMultimap
    *
    * ----------------------------------------------------------------
    * for a service to be discovred
    * port name must be : http/https/ftp
    *
    *-----------------------------------------------------------------
    * viewing only service,endpoints,pods so to avoid viewing nodes
    *
    *
    * */
    private static final Logger log = LoggerFactory.getLogger(KubernetesServiceDiscovery.class);

    private KubernetesClient client;
    private ServiceType serviceType;

    private static ServiceType defaultServiceType = ServiceType.LOADBALANCER;
    private static String loadBalancerDefaultProtocol = "http";
    private static String externalNameDefaultProtocol = "http";

    public enum ServiceType {
        CLUSTERIP, NODEPORT, LOADBALANCER, EXTERNALNAME, EXTERNALIP
    }


    KubernetesServiceDiscovery(String globalEndpoint){
        super(globalEndpoint);
        Config config = new ConfigBuilder().withMasterUrl(globalEndpoint).build();
        try {
            this.client = new DefaultKubernetesClient(config);
            this.serviceType = defaultServiceType;
        } catch (KubernetesClientException e) {
            e.printStackTrace();
        }
    }

    KubernetesServiceDiscovery(String globalEndpoint, ServiceType serviceType){
        super(globalEndpoint);
        Config config = new ConfigBuilder().withMasterUrl(globalEndpoint).build();
        try {
            this.client = new DefaultKubernetesClient(config);
            this.serviceType = serviceType;
        } catch (KubernetesClientException e) {
            e.printStackTrace();
        }
    }

    @Override
    Multimap<String, URL> listServices() throws MalformedURLException{
        ServiceList services = client.services().inAnyNamespace().list();
        addServicesToHashMap(services, null);
        return super.listServices();
    }

    @Override
    Multimap<String, URL> listServices(String namespace) throws MalformedURLException{
        ServiceList services = client.services().inNamespace(namespace).list();
        addServicesToHashMap(services, namespace);
        return super.listServices(namespace);
    }

    @Override
    Multimap<String, URL> listServices(String namespace, HashMap<String, String> criteria) throws MalformedURLException{
        ServiceList services = client.services().inNamespace(namespace).withLabels(criteria).list();
        addServicesToHashMap(services, namespace);
        return super.listServices(namespace, criteria);
    }

    @Override
    Multimap<String, URL> listServices(HashMap<String, String> criteria) throws MalformedURLException{
        ServiceList services = client.services().withLabels(criteria).list();
        addServicesToHashMap(services,null);
        return super.listServices(criteria);
    }

    protected void addServicesToHashMap(ServiceList services, String namespace) throws MalformedURLException{
        this.servicesMultimap.clear();
        List<Service> serviceItems = services.getItems();
        switch(serviceType){
            case CLUSTERIP:
                for (Service service : serviceItems) {
                    if(service.getSpec().getType().equals("ClusterIP")){
                        log.debug(service.getSpec().getType());
                        for (ServicePort servicePort : service.getSpec().getPorts()) {
                            String protocol = servicePort.getName();
                            if (protocol !=null && (protocol.equals("http")
                                    || protocol.equals("https") || protocol.equals("ftp")
                                    || protocol.equals("dns") || protocol.equals("irc"))) {
                                URL serviceURL = new URL(protocol, service.getSpec().getClusterIP(), servicePort.getPort(), "");
                                this.servicesMultimap.put(service.getMetadata().getName(), serviceURL);
                            }
                        }
                    }
                }
                break;
            case NODEPORT:
                for (Service service : serviceItems) {
                    if(service.getSpec().getType().equals("NodePort")){
                        for (ServicePort servicePort : service.getSpec().getPorts()) {
                            URL serviceURL = findNodePortServiceURLsforAPort(namespace,service.getMetadata().getName(),servicePort);
                            this.servicesMultimap.put(service.getMetadata().getName(), serviceURL);
                        }}}
                break;
            case LOADBALANCER:
                for (Service service : serviceItems) {
                    if(service.getSpec().getType().equals("LoadBalancer")){
                        LoadBalancerStatus loadBalancerStatus = service.getStatus().getLoadBalancer();
                        Iterator<LoadBalancerIngress> ingressIterator = loadBalancerStatus.getIngress().iterator();
                        if (ingressIterator.hasNext()) {
                            while (ingressIterator.hasNext()) {//Todo: maybe this also uses the service ports
                                URL serviceURL = new URL(loadBalancerDefaultProtocol+"://"+ingressIterator.next().getIp());
                                this.servicesMultimap.put(service.getMetadata().getName(), serviceURL);
                            }
                        }
                    }
                }
                break;
            case EXTERNALNAME:
                for (Service service : serviceItems) {
                    if (service.getSpec().getType().equals("ExternalName")) {//Todo: maybe this also uses the service ports
                        String externalName = (String) service.getSpec().getAdditionalProperties().get("externalName");
                        URL serviceURL = new URL(externalNameDefaultProtocol + "://" + externalName);
                        this.servicesMultimap.put(service.getMetadata().getName(), serviceURL);
                    }
                }
                break;
            case EXTERNALIP:    // Special case. not managed by Kubernetes but the cluster administrator.
                for (Service service : serviceItems) {
                    List<String> specialExternalIps = service.getSpec().getExternalIPs();
                    for (String specialExternalIp : specialExternalIps) { //special case
                        URL serviceURL = new URL("http://" + specialExternalIp);
                        this.servicesMultimap.put(service.getMetadata().getName(), serviceURL);
                    }
                }
        }

    }


    private URL findNodePortServiceURLsforAPort(String namespace, String serviceName, ServicePort servicePort){
        URL url;
        String protocol = servicePort.getName();    //kubernetes gives only tcp/udp but we need http/https
        if(protocol==null || (!protocol.equals("http")
                && !protocol.equals("https") && !protocol.equals("ftp")
                && !protocol.equals("dns") && !protocol.equals("irc"))){
            return null;
        }
        if(namespace==null){
            int nodePort = servicePort.getNodePort();
            /*
                Below, method ".inAnyNamespace()" did not support ".withName()". Therefore ".withField()" is used.
                It returns a single item list which has the only endpoint created for the service.
            */
            Endpoints endpoint = client.endpoints().inAnyNamespace().withField("metadata.name",serviceName).list().getItems().get(0);

            List<EndpointSubset> endpointSubsets = endpoint.getSubsets();
            if (endpointSubsets.isEmpty()) {  //no endpoints for the service
                return null;
            }
            for (EndpointSubset endpointSubset : endpointSubsets) {
                List<EndpointAddress> endpointAddresses = endpointSubset.getAddresses();
                if (endpointAddresses.isEmpty()) {  //no endpoints for the service
                    return null;
                }
                for (EndpointAddress endpointAddress : endpointAddresses) {
                    String podname = endpointAddress.getTargetRef().getName();
                    Pod pod = client.pods().inAnyNamespace().withField("metadata.name",podname).list().getItems().get(0); // same as comment 11 lines above
                    try {
                        url = new URL(protocol, pod.getStatus().getHostIP(), nodePort, "");
                        return url;
                    } catch (NullPointerException e) { //no pods available for this address
                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    }
                }
            }
            return null;
        } else {    // namespace given
            int portNumber = servicePort.getNodePort();
            Endpoints endpoint = client.endpoints().inNamespace(namespace).withName(serviceName).get();
            List<EndpointSubset> endpointSubsets = endpoint.getSubsets();
            if (endpointSubsets.isEmpty()) {  //no endpoints for the service
                return null;
            }
            for (EndpointSubset endpointSubset : endpointSubsets) {
                List<EndpointAddress> endpointAddresses = endpointSubset.getAddresses();
                if (endpointAddresses.isEmpty()) {  //no endpoints for the service
                    return null;
                }
                for (EndpointAddress endpointAddress : endpointAddresses) {
                    String podname = endpointAddress.getTargetRef().getName();
                    Pod pod = client.pods().inNamespace(namespace).withName(podname).get();
                    try {
                        url = new URL(protocol, pod.getStatus().getHostIP(), portNumber, "");
                        return url;
                    } catch (NullPointerException e) { //no pods available for this address
                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    }
                }
            }
            return null;
        }
    }




}
