package datawave.microservice.http.converter.xml;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.lang.NonNull;

/**
 * Reads and writes {@code application/xml} and {@code text/xml} bodies for classes annotated with the <em>javax</em> JAXB {@link XmlRootElement} or
 * {@link XmlType}. Spring 6 only ships a converter for Jakarta JAXB ({@code jakarta.xml.bind}), while the DataWave response classes shared with the WildFly web
 * services are still annotated with JAXB 2.3 ({@code javax.xml.bind}); without this converter those responses can no longer be rendered as XML.
 */
public class JavaxJaxb2RootElementHttpMessageConverter extends AbstractHttpMessageConverter<Object> {
    private final Map<Class<?>,JAXBContext> jaxbContexts = new ConcurrentHashMap<>(64);

    public JavaxJaxb2RootElementHttpMessageConverter() {
        super(MediaType.APPLICATION_XML, MediaType.TEXT_XML, new MediaType("application", "*+xml"));
    }

    @Override
    public boolean canRead(@NonNull Class<?> clazz, MediaType mediaType) {
        return (AnnotationUtils.findAnnotation(clazz, XmlRootElement.class) != null || AnnotationUtils.findAnnotation(clazz, XmlType.class) != null)
                        && canRead(mediaType);
    }

    @Override
    public boolean canWrite(@NonNull Class<?> clazz, MediaType mediaType) {
        return AnnotationUtils.findAnnotation(clazz, XmlRootElement.class) != null && canWrite(mediaType);
    }

    @Override
    protected boolean supports(@NonNull Class<?> clazz) {
        // canRead/canWrite are overridden instead
        return false;
    }

    @Override
    @NonNull
    protected Object readInternal(@NonNull Class<?> clazz, @NonNull HttpInputMessage inputMessage) throws IOException, HttpMessageNotReadableException {
        try {
            Unmarshaller unmarshaller = getJaxbContext(clazz).createUnmarshaller();
            StreamSource source = new StreamSource(inputMessage.getBody());
            if (clazz.isAnnotationPresent(XmlRootElement.class)) {
                return unmarshaller.unmarshal(source);
            }
            JAXBElement<?> element = unmarshaller.unmarshal(source, clazz);
            return element.getValue();
        } catch (JAXBException e) {
            throw new HttpMessageNotReadableException("Could not unmarshal to [" + clazz + "]: " + e.getMessage(), e, inputMessage);
        }
    }

    @Override
    protected void writeInternal(@NonNull Object o, @NonNull HttpOutputMessage outputMessage) throws IOException, HttpMessageNotWritableException {
        try {
            Marshaller marshaller = getJaxbContext(o.getClass()).createMarshaller();
            MediaType contentType = outputMessage.getHeaders().getContentType();
            if (contentType != null && contentType.getCharset() != null) {
                marshaller.setProperty(Marshaller.JAXB_ENCODING, contentType.getCharset().name());
            }
            marshaller.marshal(o, new StreamResult(outputMessage.getBody()));
        } catch (JAXBException e) {
            throw new HttpMessageNotWritableException("Could not marshal [" + o + "]: " + e.getMessage(), e);
        }
    }

    private JAXBContext getJaxbContext(Class<?> clazz) {
        return jaxbContexts.computeIfAbsent(clazz, c -> {
            try {
                return JAXBContext.newInstance(c);
            } catch (JAXBException e) {
                throw new HttpMessageConversionException("Could not create JAXBContext for class [" + c + "]: " + e.getMessage(), e);
            }
        });
    }
}
