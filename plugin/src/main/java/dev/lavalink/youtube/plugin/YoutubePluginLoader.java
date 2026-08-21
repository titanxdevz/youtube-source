package dev.lavalink.youtube.plugin;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.lava.extensions.youtuberotator.YoutubeIpRotatorSetup;
import com.sedmelluq.lava.extensions.youtuberotator.planner.*;
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.IpBlock;
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.Ipv4Block;
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.Ipv6Block;
import dev.arbjerg.lavalink.api.AudioPlayerManagerConfiguration;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.YoutubeSource;
import dev.lavalink.youtube.YoutubeSourceOptions;
import dev.lavalink.youtube.clients.ClientOptions;
import dev.lavalink.youtube.clients.skeleton.Client;
import dev.lavalink.youtube.vexanode.VexaNodeSourceManager;
import lavalink.server.config.RateLimitConfig;
import lavalink.server.config.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class YoutubePluginLoader implements AudioPlayerManagerConfiguration {
    private static final Logger log = LoggerFactory.getLogger(YoutubePluginLoader.class);

    private final YoutubeConfig youtubeConfig;
    private final ServerConfig serverConfig;
    private final RateLimitConfig ratelimitConfig;
    private final ClientProvider clientProvider;

    public YoutubePluginLoader(final YoutubeConfig youtubeConfig,
                               final ServerConfig serverConfig) {
        this.youtubeConfig = youtubeConfig;
        this.serverConfig = serverConfig;
        this.ratelimitConfig = serverConfig.getRatelimit();

        final String providerName = isV4OrNewer()
            ? "ClientProviderV4"
            : "ClientProviderV3";

        ClientProvider provider = null;

        try {
            provider = getClientProvider(providerName);
        } catch (Throwable t) {
            log.error("Failed to initialise ClientProvider class with name {}", providerName, t);
        }

        this.clientProvider = provider;

        try {
            PluginInfo.checkForNewRelease();
        } catch (Throwable ignored) {

        }
    }

    private ClientProvider getClientProvider(String name) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        Class<?> klass = Class.forName("dev.lavalink.youtube.plugin." + name);
        return (ClientProvider) klass.getDeclaredConstructor().newInstance();
    }

    private boolean isV4OrNewer() {
        try {
            Class.forName("com.sedmelluq.discord.lavaplayer.tools.ThumbnailTools");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private ClientOptions getOptionsForClient(String clientName) {
        Map<String, ClientOptions> clientOptions = youtubeConfig != null ? youtubeConfig.getClientOptions() : null;

        if (clientOptions == null || !clientOptions.containsKey(clientName)) {
            return ClientOptions.DEFAULT;
        }

        return clientOptions.get(clientName);
    }

    private IpBlock getIpBlock(String cidr) {
        if (Ipv4Block.isIpv4CidrBlock(cidr)) {
            return new Ipv4Block(cidr);
        } else if (Ipv6Block.isIpv6CidrBlock(cidr)) {
            return new Ipv6Block(cidr);
        } else {
            throw new RuntimeException("Invalid IP Block '" + cidr + "', make sure to provide a valid CIDR notation");
        }
    }

    private AbstractRoutePlanner getRoutePlanner() {
        if (ratelimitConfig == null) {
            log.debug("No ratelimit config found, skipping setup of route planner");
            return null;
        }

        if (ratelimitConfig.getIpBlocks().isEmpty()) {
            log.info("Ratelimit config present but no IP blocks were specified, route planner will not initialised.");
            return null;
        }

        final List<InetAddress> excluded = new ArrayList<>();

        try {
            for (String s : ratelimitConfig.getExcludedIps()) {
                InetAddress byName = InetAddress.getByName(s);
                excluded.add(byName);
            }
        } catch (UnknownHostException e) {
            log.warn("Failed to get excluded IP", e);
        }

        final Predicate<InetAddress> filter = (ip) -> !excluded.contains(ip);

        final List<IpBlock> ipBlocks = ratelimitConfig.getIpBlocks().stream()
            .map(this::getIpBlock)
            .collect(Collectors.toList());

        final String strategy = ratelimitConfig.getStrategy().toLowerCase(Locale.getDefault());

        switch (strategy) {
            case "rotateonban":
                return new RotatingIpRoutePlanner(ipBlocks, filter, ratelimitConfig.getSearchTriggersFail());
            case "loadbalance":
                return new BalancingIpRoutePlanner(ipBlocks, filter, ratelimitConfig.getSearchTriggersFail());
            case "nanoswitch":
                return new NanoIpRoutePlanner(ipBlocks, ratelimitConfig.getSearchTriggersFail());
            case "rotatingnanoswitch":
                return new RotatingNanoIpRoutePlanner(ipBlocks, filter, ratelimitConfig.getSearchTriggersFail());
            default:
                throw new RuntimeException("Unknown strategy '" + strategy + "'!");
        }
    }

    @Override
    public AudioPlayerManager configure(AudioPlayerManager audioPlayerManager) {
        if (youtubeConfig != null && !youtubeConfig.getEnabled()) {
            return audioPlayerManager;
        }

        final YoutubeSourceOptions sourceOptions = new YoutubeSourceOptions()
            .setAllowSearch(youtubeConfig == null || youtubeConfig.getAllowSearch())
            .setAllowDirectVideoIds(youtubeConfig == null || youtubeConfig.getAllowDirectVideoIds())
            .setAllowDirectPlaylistIds(youtubeConfig == null || youtubeConfig.getAllowDirectPlaylistIds());

        Client[] clients;

        if (youtubeConfig == null) {
            log.warn("Missing youtube config, default clients will be used.");
            clients = YoutubeAudioSourceManager.DEFAULT_CLIENTS;
        } else {
            if (clientProvider == null || youtubeConfig.getClients() == null) {
                log.warn("Missing \"youtube.clients\" or ClientProvider not initialised! Default clients will be used.");
                clients = YoutubeAudioSourceManager.DEFAULT_CLIENTS;
            } else {
                clients = clientProvider.getClients(youtubeConfig.getClients(), this::getOptionsForClient);
            }

            Pot pot = youtubeConfig.getPot();
            YoutubeRemoteCipherConfig cipherConfig = youtubeConfig.getRemoteCipher();

            if (pot != null) {
                String token = pot.getToken();
                String visitorData = pot.getVisitorData();

                if (token != null && visitorData != null) {
                    log.debug("Applying poToken and visitorData to WEB & WEBEMBEDDED client (token: {}, vd: {})", token, visitorData);
                    YoutubeSource.setPoTokenAndVisitorData(token, visitorData);
                } else if (token != null || visitorData != null) {
                    log.warn("Both \"youtube.pot.token\" and \"youtube.pot.visitorData\" must be specified and valid for pot to apply.");
                }
            }

            if (cipherConfig != null && cipherConfig.getUrl() != null) {
                log.info("\u001B[35m[VexaNode]\u001B[0m Using remote cipher server: \u001B[36m{}\u001B[0m", cipherConfig.getUrl());
                sourceOptions.setRemoteCipher(cipherConfig.getUrl(), cipherConfig.getPassword(), cipherConfig.getUserAgent());
            }
        }

        VexaNodeConfig vConfig = youtubeConfig != null && youtubeConfig.getVexanode() != null
            ? youtubeConfig.getVexanode()
            : new VexaNodeConfig();

        final VexaNodeSourceManager source;
        if (vConfig.isEnabled()) {
            dev.lavalink.youtube.vexanode.health.HealthEngine healthEngine = new dev.lavalink.youtube.vexanode.health.HealthEngine(
                vConfig.getCircuitBreakerFailureThreshold(),
                vConfig.getCircuitBreakerCooldown() * 1000L
            );
            dev.lavalink.youtube.vexanode.auth.CredentialManager credentialManager = new dev.lavalink.youtube.vexanode.auth.CredentialManager();
            dev.lavalink.youtube.vexanode.pot.PoTokenManager poTokenManager = new dev.lavalink.youtube.vexanode.pot.PoTokenManager();
            poTokenManager.setProviderUrl(vConfig.getPotProviderUrl());
            poTokenManager.setGeneratePerVideo(vConfig.isPotGeneratePerVideo());
            poTokenManager.setDefaultTtlMs(vConfig.getPotTtl() * 1000L);
            poTokenManager.setMaxCapacity(vConfig.getPotMaxCapacity());
            dev.lavalink.youtube.vexanode.cache.MetadataCache metadataCache = new dev.lavalink.youtube.vexanode.cache.MetadataCache(
                vConfig.getMetadataTtl() * 1000L,
                vConfig.getMetadataMaxCapacity()
            );
            dev.lavalink.youtube.vexanode.dedup.RequestDeduplicator requestDeduplicator = new dev.lavalink.youtube.vexanode.dedup.RequestDeduplicator();
            dev.lavalink.youtube.vexanode.resolver.ClientScorer clientScorer = new dev.lavalink.youtube.vexanode.resolver.ClientScorer(100.0, 0.02, 15.0, vConfig.isExploration());
            dev.lavalink.youtube.vexanode.resolver.AdaptiveResolver adaptiveResolver = new dev.lavalink.youtube.vexanode.resolver.AdaptiveResolver(
                healthEngine,
                credentialManager,
                poTokenManager,
                clientScorer,
                vConfig.getMaxAttempts(),
                vConfig.getMaxClientSwitches(),
                vConfig.getMaxAuthRefreshes()
            );
            dev.lavalink.youtube.vexanode.recovery.RecoveryEngine recoveryEngine = new dev.lavalink.youtube.vexanode.recovery.RecoveryEngine(healthEngine);
            dev.lavalink.youtube.vexanode.metrics.VexaNodeMetrics metrics = new dev.lavalink.youtube.vexanode.metrics.VexaNodeMetrics();

            source = new dev.lavalink.youtube.vexanode.VexaNodeSourceManager(
                sourceOptions,
                healthEngine,
                credentialManager,
                poTokenManager,
                metadataCache,
                requestDeduplicator,
                clientScorer,
                adaptiveResolver,
                recoveryEngine,
                metrics,
                clients
            );
            source.setVexaEnabled(vConfig.isEnabled());
            source.setAdaptiveEnabled(vConfig.isAdaptive());
            source.setCacheEnabled(vConfig.isMetadataCache());
            source.setDedupEnabled(vConfig.isDeduplication());

            // Register POT with PoTokenManager
            if (vConfig.getPotProviderUrl() != null && !vConfig.getPotProviderUrl().isEmpty()) {
                poTokenManager.setProviderUrl(vConfig.getPotProviderUrl());
                poTokenManager.setGeneratePerVideo(vConfig.isPotGeneratePerVideo());
                log.info("\u001B[35m[VexaNode]\u001B[0m Dynamic PO-token provider configured: \u001B[36m{}\u001B[0m", vConfig.getPotProviderUrl());
            }

            if (youtubeConfig != null && youtubeConfig.getPot() != null) {
                Pot pot = youtubeConfig.getPot();
                if (pot.getToken() != null && pot.getVisitorData() != null) {
                    poTokenManager.register("WEB", pot.getToken(), pot.getVisitorData());
                    poTokenManager.register("WEBEMBEDDED", pot.getToken(), pot.getVisitorData());
                }
            }

            // Register OAuth with CredentialManager
            if (youtubeConfig != null && youtubeConfig.getOauth() != null && youtubeConfig.getOauth().getEnabled()) {
                String rt = youtubeConfig.getOauth().getRefreshToken();
                if (rt != null && !rt.isEmpty()) {
                    credentialManager.addCredential(new dev.lavalink.youtube.vexanode.auth.OAuthCredential("account-1", rt));
                }
            }

            if (vConfig.getOauthTokens() != null && !vConfig.getOauthTokens().isEmpty()) {
                int accIdx = 2;
                for (String token : vConfig.getOauthTokens()) {
                    if (token != null && !token.trim().isEmpty()) {
                        credentialManager.addCredential(new dev.lavalink.youtube.vexanode.auth.OAuthCredential("account-" + accIdx++, token.trim()));
                    }
                }
                log.info("\u001B[35m[VexaNode]\u001B[0m Multi-Account OAuth pool registered with \u001B[32m{} credential(s)\u001B[0m", credentialManager.getAllCredentials().size());
            }
        } else {
            source = new dev.lavalink.youtube.vexanode.VexaNodeSourceManager(sourceOptions, clients);
            source.setVexaEnabled(false);
        }

        final AbstractRoutePlanner routePlanner = getRoutePlanner();

        if (routePlanner != null) {
            final int retryLimit = ratelimitConfig.getRetryLimit();
            final YoutubeIpRotatorSetup rotator = new YoutubeIpRotatorSetup(routePlanner)
                .forConfiguration(source.getHttpInterfaceManager(), false)
                .withMainDelegateFilter(source.getContextFilter());

            if (retryLimit == 0) {
                rotator.withRetryLimit(Integer.MAX_VALUE);
            } else if (retryLimit > 0) {
                rotator.withRetryLimit(retryLimit);
            }

            rotator.setup();
        }

        Integer playlistLoadLimit = serverConfig.getYoutubePlaylistLoadLimit();

        if (playlistLoadLimit != null && playlistLoadLimit > 0) {
            source.setPlaylistPageCount(playlistLoadLimit);
        }

        if (youtubeConfig != null && youtubeConfig.getOauth() != null) {
            YoutubeOauthConfig oauthConfig = youtubeConfig.getOauth();

            if (oauthConfig.getEnabled()) {
                log.debug("Configuring youtube oauth integration with token: \"{}\" skipInitialization: {}", oauthConfig.getRefreshToken(), oauthConfig.getSkipInitialization());
                source.useOauth2(oauthConfig.getRefreshToken(), oauthConfig.getSkipInitialization());
            }
        }

        log.info("\u001B[35m[VexaNode]\u001B[0m YouTube source initialised with clients: \u001B[36m{}\u001B[0m (\u001B[32madaptive: {}\u001B[0m)",
            Arrays.stream(source.getClients()).map(Client::getIdentifier).collect(Collectors.joining(", ")),
            source.isAdaptiveEnabled());
        audioPlayerManager.registerSourceManager(source);
        return audioPlayerManager;
    }
}
