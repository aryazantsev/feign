/*
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feign;

import dagger.Provides;
import feign.InvocationHandlerFactory.MethodHandler;
import feign.Request.Options;
import feign.codec.Decoder;
import feign.codec.EncodeException;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;

import javax.inject.Inject;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static feign.Util.checkArgument;
import static feign.Util.checkNotNull;

@SuppressWarnings("rawtypes")
public class ReflectiveFeign extends Feign {

  private final ParseHandlersByName targetToHandlersByName;
  private final InvocationHandlerFactory factory;

  @Inject ReflectiveFeign(ParseHandlersByName targetToHandlersByName, InvocationHandlerFactory factory) {
    this.targetToHandlersByName = targetToHandlersByName;
    this.factory = factory;
  }

  /**
   * creates an api binding to the {@code target}. As this invokes reflection,
   * care should be taken to cache the result.
   */
  @SuppressWarnings("unchecked") @Override public <T> T newInstance(Target<T> target) {
    Map<String, MethodHandler> nameToHandler = targetToHandlersByName.apply(target);
    Map<Method, MethodHandler> methodToHandler = new LinkedHashMap<Method, MethodHandler>();
    for (Method method : target.type().getDeclaredMethods()) {
      if (method.getDeclaringClass() == Object.class)
        continue;
      methodToHandler.put(method, nameToHandler.get(Feign.configKey(method)));
    }
    InvocationHandler handler = factory.create(target, methodToHandler);
    return (T) Proxy.newProxyInstance(target.type().getClassLoader(), new Class<?>[]{target.type()}, handler);
  }

  static class FeignInvocationHandler implements InvocationHandler {

    private final Target target;
    private final Map<Method, MethodHandler> dispatch;

    FeignInvocationHandler(Target target, Map<Method, MethodHandler> dispatch) {
      this.target = checkNotNull(target, "target");
      this.dispatch = checkNotNull(dispatch, "dispatch for %s", target);
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if ("equals".equals(method.getName())) {
        try {
          Object otherHandler = args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
          return equals(otherHandler);
        } catch (IllegalArgumentException e) {
          return false;
        }
      }
      if ("hashCode".equals(method.getName())) {
        return hashCode();
      }
      return dispatch.get(method).invoke(args);
    }

    @Override public int hashCode() {
      return target.hashCode();
    }

    @Override public boolean equals(Object other) {
      if (other instanceof FeignInvocationHandler) {
        FeignInvocationHandler that = (FeignInvocationHandler) other;
        return this.target.equals(that.target);
      }
      return false;
    }

    @Override public String toString() {
      return "target(" + target + ")";
    }
  }

  @dagger.Module(complete = false, injects = {Feign.class, SynchronousMethodHandler.Factory.class}, library = true)
  public static class Module {
    @Provides(type = Provides.Type.SET_VALUES) Set<RequestInterceptor> noRequestInterceptors() {
      return Collections.emptySet();
    }

    @Provides Feign provideFeign(ReflectiveFeign in) {
      return in;
    }
  }

  static final class ParseHandlersByName {
    private final Contract contract;
    private final Options options;
    private final Encoder encoder;
    private final Decoder decoder;
    private final ErrorDecoder errorDecoder;
    private final SynchronousMethodHandler.Factory factory;

    @SuppressWarnings("unchecked")
    @Inject ParseHandlersByName(Contract contract, Options options, Encoder encoder, Decoder decoder,
                                ErrorDecoder errorDecoder, SynchronousMethodHandler.Factory factory) {
      this.contract = contract;
      this.options = options;
      this.factory = factory;
      this.errorDecoder = errorDecoder;
      this.encoder = checkNotNull(encoder, "encoder");
      this.decoder = checkNotNull(decoder, "decoder");
    }

    public Map<String, MethodHandler> apply(Target key) {
      List<MethodMetadata> metadata = contract.parseAndValidatateMetadata(key.type());
      Map<String, MethodHandler> result = new LinkedHashMap<String, MethodHandler>();
      for (MethodMetadata md : metadata) {
        BuildTemplateByResolvingArgs buildTemplate;
        if (!md.formParams().isEmpty() && md.template().bodyTemplate() == null) {
          buildTemplate = new BuildFormEncodedTemplateFromArgs(md, encoder);
        } else if (md.bodyIndex() != null) {
          buildTemplate = new BuildEncodedTemplateFromArgs(md, encoder);
        } else {
          buildTemplate = new BuildTemplateByResolvingArgs(md);
        }
        result.put(md.configKey(), factory.create(key, md, buildTemplate, options, decoder, errorDecoder));
      }
      return result;
    }
  }

  private static class BuildTemplateByResolvingArgs implements RequestTemplate.Factory {
    protected final MethodMetadata metadata;

    private BuildTemplateByResolvingArgs(MethodMetadata metadata) {
      this.metadata = metadata;
    }

    @Override public RequestTemplate create(Object[] argv) {
      RequestTemplate mutable = new RequestTemplate(metadata.template());
      if (metadata.urlIndex() != null) {
        int urlIndex = metadata.urlIndex();
        checkArgument(argv[urlIndex] != null, "URI parameter %s was null", urlIndex);
        mutable.insert(0, String.valueOf(argv[urlIndex]));
      }
      Map<String, Object> varBuilder = new LinkedHashMap<String, Object>();
      for (Entry<Integer, Collection<String>> entry : metadata.indexToName().entrySet()) {
        Object value = argv[entry.getKey()];
        if (value != null) { // Null values are skipped.
          for (String name : entry.getValue())
            varBuilder.put(name, value);
        }
      }
      return resolve(argv, mutable, varBuilder);
    }

    protected RequestTemplate resolve(Object[] argv, RequestTemplate mutable, Map<String, Object> variables) {
      return mutable.resolve(variables);
    }
  }

  private static class BuildFormEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {
    private final Encoder encoder;

    private BuildFormEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder) {
      super(metadata);
      this.encoder = encoder;
    }

    @Override
    protected RequestTemplate resolve(Object[] argv, RequestTemplate mutable, Map<String, Object> variables) {
      Map<String, Object> formVariables = new LinkedHashMap<String, Object>();
      for (Entry<String, Object> entry : variables.entrySet()) {
        if (metadata.formParams().contains(entry.getKey()))
          formVariables.put(entry.getKey(), entry.getValue());
      }
      try {
        encoder.encode(formVariables, mutable);
      } catch (EncodeException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EncodeException(e.getMessage(), e);
      }
      return super.resolve(argv, mutable, variables);
    }
  }

  private static class BuildEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {
    private final Encoder encoder;

    private BuildEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder) {
      super(metadata);
      this.encoder = encoder;
    }

    @Override
    protected RequestTemplate resolve(Object[] argv, RequestTemplate mutable, Map<String, Object> variables) {
      Object body = argv[metadata.bodyIndex()];
      checkArgument(body != null, "Body parameter %s was null", metadata.bodyIndex());
      try {
        encoder.encode(body, mutable);
      } catch (EncodeException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EncodeException(e.getMessage(), e);
      }
      return super.resolve(argv, mutable, variables);
    }
  }
}
