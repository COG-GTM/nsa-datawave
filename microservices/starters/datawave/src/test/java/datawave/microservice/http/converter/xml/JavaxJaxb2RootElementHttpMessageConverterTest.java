package datawave.microservice.http.converter.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.http.MockHttpOutputMessage;

import datawave.webservice.result.VoidResponse;

public class JavaxJaxb2RootElementHttpMessageConverterTest {

    private final JavaxJaxb2RootElementHttpMessageConverter converter = new JavaxJaxb2RootElementHttpMessageConverter();

    @Test
    public void supportsJavaxAnnotatedResponses() {
        assertTrue(converter.canWrite(VoidResponse.class, MediaType.APPLICATION_XML));
        assertTrue(converter.canRead(VoidResponse.class, MediaType.APPLICATION_XML));
        assertFalse(converter.canWrite(String.class, MediaType.APPLICATION_XML));
        assertFalse(converter.canRead(VoidResponse.class, MediaType.APPLICATION_JSON));
    }

    @Test
    public void roundTripsAResponse() throws Exception {
        VoidResponse response = new VoidResponse();
        response.addMessage("hello");
        MockHttpOutputMessage out = new MockHttpOutputMessage();
        out.getHeaders().setContentType(MediaType.APPLICATION_XML);
        converter.write(response, MediaType.APPLICATION_XML, out);
        String xml = out.getBodyAsString(StandardCharsets.UTF_8);
        assertTrue(xml.contains("<Messages>hello</Messages>"), xml);

        MockHttpInputMessage in = new MockHttpInputMessage(xml.getBytes(StandardCharsets.UTF_8));
        in.getHeaders().setContentType(MediaType.APPLICATION_XML);
        VoidResponse read = (VoidResponse) converter.read(VoidResponse.class, in);
        assertEquals(response.getMessages(), read.getMessages());
    }

    @Test
    public void rejectsExternalEntities() {
        // @formatter:off
        String xxe = "<?xml version=\"1.0\"?>"
                        + "<!DOCTYPE VoidResponse [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>"
                        + "<VoidResponse xmlns=\"http://webservice.datawave.nsa/v1\"><Messages>&xxe;</Messages></VoidResponse>";
        // @formatter:on
        MockHttpInputMessage in = new MockHttpInputMessage(xxe.getBytes(StandardCharsets.UTF_8));
        in.getHeaders().setContentType(MediaType.APPLICATION_XML);
        assertThrows(HttpMessageNotReadableException.class, () -> converter.read(VoidResponse.class, in));
    }
}
