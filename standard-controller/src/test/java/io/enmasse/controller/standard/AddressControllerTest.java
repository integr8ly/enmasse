/*
 * Copyright 2016, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.controller.standard;

import io.enmasse.address.model.Address;
import io.enmasse.address.model.AddressResolver;
import io.enmasse.k8s.api.AddressApi;
import io.enmasse.k8s.api.EventLogger;
import io.enmasse.k8s.api.TestSchemaApi;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.openshift.client.OpenShiftClient;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.collections.Sets;
import org.mockito.internal.verification.VerificationModeFactory;

import java.util.*;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AddressControllerTest {
    private Kubernetes mockHelper;
    private AddressApi mockApi;
    private AddressController controller;
    private OpenShiftClient mockClient;
    private AddressClusterGenerator mockGenerator;

    @Before
    public void setUp() {
        mockHelper = mock(Kubernetes.class);
        mockGenerator = mock(AddressClusterGenerator.class);
        mockApi = mock(AddressApi.class);
        mockClient = mock(OpenShiftClient.class);
        TestSchemaApi testSchemaApi = new TestSchemaApi();
        AddressResolver testResolver = new AddressResolver(testSchemaApi.getSchema(), testSchemaApi.getSchema().findAddressSpaceType("type1").get());
        EventLogger eventLogger = mock(EventLogger.class);

        controller = new AddressController("me", mockApi, testResolver, mockHelper, mockGenerator, null, eventLogger);
    }

    private Address createAddress(String address, String type, String plan) {
        return new Address.Builder()
                .setName(address)
                .setAddress(address)
                .setAddressSpace("unknown")
                .setType(type)
                .setPlan(plan)
                .setUuid(UUID.randomUUID().toString())
                .build();

    }

    @Test
    public void testClusterIsCreated() throws Exception {
        Address queue = createAddress("myqueue", "queue", "plan1");
        KubernetesList resources = new KubernetesList();
        resources.setItems(Arrays.asList(new ConfigMap()));
        AddressCluster cluster = new AddressCluster("myqueue", resources);

        when(mockHelper.listClusters()).thenReturn(Collections.emptyList());
        when(mockGenerator.generateCluster("myqueue", Collections.singleton(queue))).thenReturn(cluster);
        ArgumentCaptor<Set<io.enmasse.address.model.Address>> arg = ArgumentCaptor.forClass(Set.class);

        controller.resourcesUpdated(Collections.singleton(queue));
        verify(mockGenerator).generateCluster(eq("myqueue"), arg.capture());
        assertThat(arg.getValue(), hasItem(queue));
        verify(mockHelper).create(resources);
    }


    @Test
    public void testNodesAreRetained() throws Exception {
        Address queue = createAddress("myqueue", "queue", "plan1");

        KubernetesList resources = new KubernetesList();
        resources.setItems(Arrays.asList(new ConfigMap()));
        AddressCluster existing = new AddressCluster(queue.getAddress(), resources);
        when(mockHelper.listClusters()).thenReturn(Collections.singletonList(existing));

        Address newQueue = createAddress("newqueue", "queue", "plan1");
        AddressCluster newCluster = new AddressCluster(newQueue.getAddress(), resources);

        when(mockGenerator.generateCluster("newqueue", Collections.singleton(newQueue))).thenReturn(newCluster);
        ArgumentCaptor<Set<io.enmasse.address.model.Address>> arg = ArgumentCaptor.forClass(Set.class);

        controller.resourcesUpdated(Sets.newSet(queue, newQueue));

        verify(mockGenerator).generateCluster(anyString(), arg.capture());
        assertThat(arg.getValue(), is(Sets.newSet(newQueue)));
        verify(mockHelper).create(resources);
    }

    @Test
    public void testClusterIsRemoved() throws Exception {
        Address queue = createAddress("myqueue", "queue", "plan1");

        KubernetesList resources = new KubernetesList();
        resources.setItems(Arrays.asList(new ConfigMap()));
        AddressCluster existing = new AddressCluster("myqueue", resources);

        Address newQueue = createAddress("newqueue", "queue", "plan1");

        AddressCluster newCluster = new AddressCluster("newqueue", resources);

        when(mockHelper.listClusters()).thenReturn(Arrays.asList(existing, newCluster));

        controller.resourcesUpdated(Collections.singleton(newQueue));

        verify(mockHelper, VerificationModeFactory.atMost(1)).delete(resources);
    }

    @Test
    public void testAddressesAreGrouped() throws Exception {
        Address addr0 = createAddress("myqueue0", "queue", "plan1");
        Address addr1 = createAddress("myqueue1", "queue", "pooled-inmemory");
        Address addr2 = createAddress("myqueue2", "queue", "pooled-inmemory");
        Address addr3 = createAddress("myqueue3", "queue", "plan1");

        KubernetesList resources = new KubernetesList();
        resources.setItems(Arrays.asList(new ConfigMap()));
        AddressCluster existing = new AddressCluster("myqueue0", resources);

        when(mockHelper.listClusters()).thenReturn(Collections.singletonList(existing));
        ArgumentCaptor<Set<io.enmasse.address.model.Address>> arg = ArgumentCaptor.forClass(Set.class);
        when(mockGenerator.generateCluster(anyString(), arg.capture())).thenReturn(new AddressCluster("foo", resources));

        controller.resourcesUpdated(Sets.newSet(addr0, addr1, addr2, addr3));

        Set<io.enmasse.address.model.Address> generated = arg.getAllValues().stream().flatMap(Collection::stream).collect(Collectors.toSet());
        assertThat(generated.size(), is(3));
    }

    @Test
    public void testAddressesAreNotRecreated() throws Exception {
        Address address = createAddress("addr1", "anycast", "plan1");
        Address newAddress = createAddress("addr2", "anycast", "plan1");

        KubernetesList resources = new KubernetesList();
        when(mockGenerator.generateCluster(eq("addr1"), anySet())).thenReturn(new AddressCluster("addr1", resources));
        when(mockGenerator.generateCluster(eq("addr2"), anySet())).thenReturn(new AddressCluster("addr2", resources));

        doThrow(new KubernetesClientException("Unable to replace resource")).when(mockApi).replaceAddress(address);

        try {
            controller.resourcesUpdated(Sets.newSet(address, newAddress));

            ArgumentCaptor<Address> addressArgumentCaptor = ArgumentCaptor.forClass(Address.class);
            verify(mockApi, times(2)).replaceAddress(addressArgumentCaptor.capture());
            List<Address> replaced = addressArgumentCaptor.getAllValues();
            assertThat(replaced, hasItem(newAddress));
        } catch (KubernetesClientException e) {
            fail("Should not throw exception with multiple items");
        }
    }
}
