package org.mule.tooling.internal;

import static org.mule.runtime.api.deployment.meta.Product.MULE;
import static org.mule.runtime.container.api.ModuleRepository.createModuleRepository;
import static org.mule.runtime.core.api.config.MuleManifest.getProductVersion;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.startIfNeeded;
import static org.mule.runtime.core.api.util.UUID.getUUID;
import static org.mule.runtime.deployment.model.api.artifact.ArtifactDescriptorConstants.MULE_LOADER_ID;
import static org.mule.runtime.module.artifact.activation.api.extension.discovery.ExtensionModelDiscoverer.defaultExtensionModelDiscoverer;
import static org.mule.runtime.module.artifact.activation.api.extension.discovery.ExtensionModelDiscoverer.discoverRuntimeExtensionModels;
import static org.mule.runtime.module.deployment.impl.internal.maven.AbstractMavenClassLoaderModelLoader.CLASSLOADER_MODEL_MAVEN_REACTOR_RESOLVER;
import static org.mule.runtime.module.deployment.impl.internal.maven.MavenUtils.createDeployablePomFile;
import static org.mule.runtime.module.deployment.impl.internal.maven.MavenUtils.createDeployablePomProperties;
import static org.mule.runtime.module.deployment.impl.internal.maven.MavenUtils.getPomModelFromJar;
import static org.mule.runtime.module.extension.internal.ExtensionProperties.DISABLE_COMPONENT_IGNORE;

import static java.lang.Boolean.TRUE;
import static java.lang.Boolean.valueOf;
import static java.lang.String.format;
import static java.lang.System.nanoTime;
import static java.nio.file.Files.createTempDirectory;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.toSet;

import static com.google.common.collect.ImmutableSet.copyOf;
import static org.apache.commons.io.FileUtils.deleteDirectory;
import static org.apache.commons.io.FileUtils.deleteQuietly;

import org.mule.maven.client.api.MavenReactorResolver;
import org.mule.maven.client.api.model.BundleDescriptor;
import org.mule.runtime.api.deployment.meta.MuleApplicationModel;
import org.mule.runtime.api.deployment.meta.MuleArtifactLoaderDescriptor;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.meta.MuleVersion;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.module.artifact.activation.api.classloader.ArtifactClassLoaderResolver;
import org.mule.runtime.module.artifact.activation.api.extension.discovery.ExtensionDiscoveryRequest;
import org.mule.runtime.module.artifact.activation.api.extension.discovery.ExtensionModelDiscoverer;
import org.mule.runtime.module.artifact.activation.api.extension.discovery.ExtensionModelLoaderRepository;
import org.mule.runtime.module.artifact.activation.internal.extension.discovery.DefaultExtensionModelLoaderRepository;
import org.mule.runtime.module.artifact.api.classloader.MuleDeployableArtifactClassLoader;
import org.mule.runtime.module.artifact.api.descriptor.ApplicationDescriptor;
import org.mule.runtime.module.artifact.api.descriptor.ArtifactPluginDescriptor;
import org.mule.tooling.api.ExtensionModelService;
import org.mule.tooling.api.ToolingException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableMap;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link ExtensionModelService}.
 *
 * @since 4.0
 */
public class DefaultExtensionModelService implements ExtensionModelService {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultExtensionModelService.class);

  private static final String MULE_APPLICATION = "mule-application";
  private static final String MAVEN_MODEL_VERSION = "4.0.0";

  private ExtensionModelDiscoverer extensionModelDiscoverer;
  private final MuleArtifactResourcesRegistry muleArtifactResourcesRegistry;

  private final List<ExtensionModel> runtimeExtensionModels = new ArrayList<>();

  public DefaultExtensionModelService(MuleArtifactResourcesRegistry muleArtifactResourcesRegistry) {
    requireNonNull(muleArtifactResourcesRegistry, "muleArtifactResourcesRegistry cannot be null");

    this.muleArtifactResourcesRegistry = muleArtifactResourcesRegistry;
    this.runtimeExtensionModels.addAll(new ArrayList<>(discoverRuntimeExtensionModels()));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<ExtensionModel> loadRuntimeExtensionModels() {
    return runtimeExtensionModels;
  }

  @Override
  public PluginResources loadExtensionData(File pluginJarFile) {
    long startTime = nanoTime();
    org.mule.runtime.module.artifact.api.descriptor.BundleDescriptor bundleDescriptor =
        readArtifactPluginDescriptor(pluginJarFile).getBundleDescriptor();

    Map<String, Object> classLoaderModelAttributes = new HashMap<>();
    classLoaderModelAttributes
        .put(CLASSLOADER_MODEL_MAVEN_REACTOR_RESOLVER,
             new PluginFileMavenReactor(bundleDescriptor, pluginJarFile, muleArtifactResourcesRegistry.getWorkingDirectory()));

    BundleDescriptor pluginDescriptor = new BundleDescriptor.Builder()
        .setGroupId(bundleDescriptor.getGroupId())
        .setGroupId(bundleDescriptor.getGroupId())
        .setArtifactId(bundleDescriptor.getArtifactId())
        .setVersion(bundleDescriptor.getVersion())
        .setClassifier(bundleDescriptor.getClassifier().orElse(null))
        .build();
    PluginResources extensionInformationOptional =
        withTemporaryApplication(pluginDescriptor, classLoaderModelAttributes,
                                 (artifactPluginDescriptor,
                                  artifactClassLoader,
                                  properties) -> loadExtensionData(artifactPluginDescriptor,
                                                                   artifactClassLoader,
                                                                   properties),
                                 null);
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Extension model for {} loaded in {}ms", pluginJarFile, NANOSECONDS.toMillis(nanoTime() - startTime));
    }

    return extensionInformationOptional;
  }

  class PluginFileMavenReactor implements MavenReactorResolver {

    private static final String POM_XML = "pom.xml";
    private static final String POM = "pom";

    private final org.mule.runtime.module.artifact.api.descriptor.BundleDescriptor descriptor;
    private final File mulePluginJarFile;
    private final File temporaryFolder;

    public PluginFileMavenReactor(org.mule.runtime.module.artifact.api.descriptor.BundleDescriptor descriptor,
                                  File mulePluginJarFile, File workingDirectory) {
      this.descriptor = descriptor;
      this.mulePluginJarFile = mulePluginJarFile;

      this.temporaryFolder = new File(workingDirectory, getUUID());
      this.temporaryFolder.mkdirs();

      Model model = getPomModelFromJar(mulePluginJarFile);
      MavenXpp3Writer writer = new MavenXpp3Writer();
      try (FileOutputStream outputStream = new FileOutputStream(new File(temporaryFolder, POM_XML))) {
        writer.write(outputStream, model);
      } catch (IOException e) {
        throw new MuleRuntimeException(e);
      }

    }

    @Override
    public File findArtifact(org.mule.maven.client.api.model.BundleDescriptor bundleDescriptor) {
      if (checkArtifact(bundleDescriptor)) {
        if (bundleDescriptor.getType().equals(POM)) {
          return new File(temporaryFolder, POM_XML);
        } else {
          return mulePluginJarFile;
        }
      }
      return null;
    }

    @Override
    public List<String> findVersions(org.mule.maven.client.api.model.BundleDescriptor bundleDescriptor) {
      if (checkArtifact(bundleDescriptor)) {
        return singletonList(descriptor.getVersion());
      }
      return emptyList();
    }

    private boolean checkArtifact(org.mule.maven.client.api.model.BundleDescriptor bundleDescriptor) {
      return descriptor.getGroupId().equals(bundleDescriptor.getGroupId())
          && descriptor.getArtifactId().equals(bundleDescriptor.getArtifactId())
          && descriptor.getVersion().equals(bundleDescriptor.getVersion());
    }


    public void dispose() {
      try {
        deleteDirectory(temporaryFolder);
      } catch (IOException e) {
        // Nothing to do...
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BundleDescriptor readBundleDescriptor(File pluginFile) {
    org.mule.runtime.module.artifact.api.descriptor.BundleDescriptor bundleDescriptor =
        readArtifactPluginDescriptor(pluginFile).getBundleDescriptor();
    return new BundleDescriptor.Builder()
        .setGroupId(bundleDescriptor.getGroupId())
        .setArtifactId(bundleDescriptor.getArtifactId())
        .setBaseVersion(bundleDescriptor.getVersion())
        .setVersion(bundleDescriptor.getVersion())
        .setType(bundleDescriptor.getType())
        .setClassifier(bundleDescriptor.getClassifier().orElse(null))
        .build();
  }

  private ArtifactPluginDescriptor readArtifactPluginDescriptor(File pluginFile) {
    try {
      ArtifactPluginDescriptor artifactPluginDescriptor;
      artifactPluginDescriptor = muleArtifactResourcesRegistry.getArtifactPluginDescriptorLoader().load(pluginFile);
      return artifactPluginDescriptor;
    } catch (Exception e) {
      throw new ToolingException("Error while loading ExtensionModel for plugin: " + pluginFile.getAbsolutePath(), e);
    }
  }


  @Override
  public PluginResources loadExtensionData(BundleDescriptor pluginDescriptor,
                                           MuleVersion muleVersion) {
    long startTime = nanoTime();
    PluginResources extensionInformationOptional =
        withTemporaryApplication(pluginDescriptor, emptyMap(),
                                 (artifactPluginDescriptor,
                                  artifactClassLoader,
                                  properties) -> loadExtensionData(artifactPluginDescriptor,
                                                                   artifactClassLoader,
                                                                   properties),
                                 muleVersion);
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Extension model for {} loaded in {}ms", pluginDescriptor, NANOSECONDS.toMillis(nanoTime() - startTime));
    }

    return extensionInformationOptional;
  }

  private PluginResources withTemporaryApplication(BundleDescriptor pluginDescriptor,
                                                   Map<String, Object> classLoaderModelLoaderAttributes,
                                                   TemporaryApplicationFunction action,
                                                   MuleVersion muleVersion) {
    String uuid = getUUID();
    String applicationName = uuid + "-extension-model-temp-app";
    File applicationFolder = new File(muleArtifactResourcesRegistry.getWorkingDirectory(), applicationName);
    try {
      createPomFile(pluginDescriptor, uuid, applicationFolder);
      MuleApplicationModel muleApplicationModel = new MuleApplicationModel.MuleApplicationModelBuilder()
          .setMinMuleVersion(muleVersion != null ? muleVersion.toString() : getProductVersion())
          .setName(applicationName)
          .setRequiredProduct(MULE)
          .withBundleDescriptorLoader(new MuleArtifactLoaderDescriptor(MULE_LOADER_ID, emptyMap()))
          .withClassLoaderModelDescriptorLoader(new MuleArtifactLoaderDescriptor(MULE_LOADER_ID,
                                                                                 classLoaderModelLoaderAttributes))
          .build();
      ApplicationDescriptor applicationDescriptor = muleArtifactResourcesRegistry.getApplicationDescriptorFactory()
          .createArtifact(applicationFolder, empty(), muleApplicationModel);


      ArtifactClassLoaderResolver artifactClassLoaderResolver = ArtifactClassLoaderResolver
          .classLoaderResolver(createModuleRepository(ArtifactClassLoaderResolver.class
              .getClassLoader(), createTempDir()), (empty) -> applicationFolder);

      muleArtifactResourcesRegistry.getPluginDependenciesResolver()
          .resolve(emptySet(), new ArrayList<>(applicationDescriptor.getPlugins()), false);

      MuleDeployableArtifactClassLoader artifactClassLoader =
          artifactClassLoaderResolver.createApplicationClassLoader(applicationDescriptor,
                                                                   muleArtifactResourcesRegistry::getContainerArtifactClassLoader);

      try {
        ArtifactPluginDescriptor artifactPluginDescriptor = artifactClassLoader.getArtifactPluginClassLoaders().stream()
            .filter(artifactPluginClassLoader -> artifactPluginClassLoader.getArtifactDescriptor().getBundleDescriptor()
                .getGroupId()
                .equals(pluginDescriptor.getGroupId())
                && artifactPluginClassLoader.getArtifactDescriptor().getBundleDescriptor().getArtifactId()
                    .equals(pluginDescriptor.getArtifactId()))
            .findFirst().orElseThrow(() -> new IllegalStateException(format("Couldn't find plugin descriptor: %s",
                                                                            pluginDescriptor)))
            .getArtifactDescriptor();

        return action.call(artifactPluginDescriptor, artifactClassLoader, pluginDescriptor.getProperties());
      } catch (Exception e) {
        throw new ToolingException(e);
      } finally {
        if (artifactClassLoader != null) {
          artifactClassLoader.dispose();
        }
      }
    } catch (ToolingException e) {
      throw e;
    } catch (Exception e) {
      throw new ToolingException(e);
    } finally {
      deleteQuietly(applicationFolder);
    }
  }

  private File createTempDir() throws IOException {
    File tempFolder = createTempDirectory(null).toFile();
    File moduleDiscovererTemporaryFolder = new File(tempFolder, ".moduleDiscoverer");
    if (!moduleDiscovererTemporaryFolder.mkdir()) {
      throw new IOException("Error while generating class loaders, cannot create directory "
          + moduleDiscovererTemporaryFolder.getAbsolutePath());
    }
    return moduleDiscovererTemporaryFolder;
  }

  private void createPomFile(BundleDescriptor pluginDescriptor, String uuid, File applicationFolder) {
    Model model = new Model();
    model.setGroupId(uuid);
    model.setArtifactId(uuid);
    model.setVersion(getProductVersion());
    model.setPackaging(MULE_APPLICATION);
    model.setModelVersion(MAVEN_MODEL_VERSION);

    Dependency dependency = new Dependency();
    dependency.setGroupId(pluginDescriptor.getGroupId());
    dependency.setArtifactId(pluginDescriptor.getArtifactId());
    dependency.setVersion(pluginDescriptor.getVersion());
    dependency.setClassifier(pluginDescriptor.getClassifier().get());
    dependency.setType(pluginDescriptor.getType());

    model.getDependencies().add(dependency);

    createDeployablePomFile(applicationFolder, model);
    createDeployablePomProperties(applicationFolder, model);
  }

  @FunctionalInterface
  interface TemporaryApplicationFunction {

    PluginResources call(ArtifactPluginDescriptor artifactPluginDescriptor,
                         MuleDeployableArtifactClassLoader artifactClassLoader,
                         Map<String, String> properties);
  }

  private PluginResources loadExtensionData(ArtifactPluginDescriptor artifactPluginDescriptor,
                                            MuleDeployableArtifactClassLoader artifactClassLoader,
                                            Map<String, String> properties) {
    try {
      ArrayList<URL> resources = new ArrayList<URL>();
      artifactPluginDescriptor.getClassLoaderModel().getExportedResources().forEach(resource -> {
        if (artifactClassLoader.getParent().getResource(resource) != null) {
          resources.add(artifactClassLoader.getParent().getResource(resource));
        }
      });
      ExtensionModelLoaderRepository extensionModelLoaderRepository =
          ExtensionModelLoaderRepository
              .getExtensionModelLoaderManager(muleArtifactResourcesRegistry.getContainerArtifactClassLoader().getClassLoader());
      startIfNeeded(extensionModelLoaderRepository);
      final Set<ExtensionModel> loadedExtensionInformation =
          discoverPluginsExtensionModel(artifactClassLoader, extensionModelLoaderRepository, properties);
      return new PluginResources(loadedExtensionInformation, resources);
    } catch (Exception e) {
      throw new ToolingException(e);
    } finally {
      if (artifactClassLoader != null) {
        artifactClassLoader.dispose();
      }
    }
  }

  private Set<ExtensionModel> discoverPluginsExtensionModel(MuleDeployableArtifactClassLoader artifactClassLoader,
                                                            ExtensionModelLoaderRepository extensionModelLoaderRepository,
                                                            Map<String, String> properties) {
    Set<ArtifactPluginDescriptor> artifactPluginDescriptors =
        artifactClassLoader.getArtifactPluginClassLoaders().stream()
            .map(a -> effectiveModel(properties, a.getArtifactDescriptor())).collect(toSet());
    extensionModelDiscoverer = defaultExtensionModelDiscoverer(artifactClassLoader, extensionModelLoaderRepository);
    ExtensionDiscoveryRequest request = ExtensionDiscoveryRequest.builder()
        .setArtifactPlugins(artifactPluginDescriptors)
        .setParentArtifactExtensions(copyOf(loadRuntimeExtensionModels()))
        .build();
    return extensionModelDiscoverer.discoverPluginsExtensionModels(request);
  }

  private ArtifactPluginDescriptor effectiveModel(Map<String, String> properties, ArtifactPluginDescriptor artifactDescriptor) {
    if (valueOf(properties.getOrDefault(DISABLE_COMPONENT_IGNORE, "false"))) {
      if (LOGGER.isInfoEnabled()) {
        LOGGER.info(format("Loading effective model for '%s'", artifactDescriptor.getBundleDescriptor()));
      }
      artifactDescriptor.getExtensionModelDescriptorProperty()
          .ifPresent(extensionModelDescriptorProperty -> extensionModelDescriptorProperty
              .addAttributes(ImmutableMap.of(DISABLE_COMPONENT_IGNORE, TRUE)));
    }
    return artifactDescriptor;
  }


}
