package com.elmakers.mine.bukkit.spell;

import com.elmakers.mine.bukkit.api.action.CastContext;
import com.elmakers.mine.bukkit.api.magic.Mage;
import com.elmakers.mine.bukkit.api.spell.TargetType;
import com.elmakers.mine.bukkit.block.MaterialAndData;
import com.elmakers.mine.bukkit.block.MaterialBrush;
import com.elmakers.mine.bukkit.utility.ConfigurationUtils;
import com.elmakers.mine.bukkit.utility.Target;
import com.elmakers.mine.bukkit.utility.Targeting;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class TargetingSpell extends BaseSpell {
    // This differs from CompatibilityUtils.MAX_ENTITY_RANGE,
    // block targeting can theoretically go farther
    private static final int  MAX_RANGE  = 511;

    private Targeting                           targeting               = new Targeting();

    private Location                            targetLocation          = null;
    protected Location                          targetLocation2         = null;
    private Entity								targetEntity            = null;

    private boolean								targetNPCs				= false;
    private boolean								targetArmorStands		= false;
    private boolean								targetInvisible			= true;
    private boolean								targetUnknown			= true;
    protected Class<? extends Entity>           targetEntityType        = null;
    protected Set<EntityType>                   targetEntityTypes       = null;
    protected Material                          targetContents          = null;
    protected double 		                    targetBreakables	    = 0;
    protected boolean                           instantBlockEffects     = false;
    private int                                 range                   = 0;

    private boolean                             checkProtection         = false;
    private int                                 damageResistanceProtection = 0;

    private boolean                             allowMaxRange           = false;
    private boolean                             bypassBackfire          = false;

    private Set<Material>                       targetThroughMaterials  = new HashSet<Material>();
    private Set<Material>                       targetableMaterials     = null;
    private Set<Material>                       reflectiveMaterials     = null;
    private boolean                             reverseTargeting        = false;
    private boolean                             originAtTarget          = false;

    protected void initializeTargeting()
    {
        targeting.reset();
        reverseTargeting = false;
        targetLocation = null;
        targetLocation2 = null;
    }

    public String getMessage(String messageKey, String def) {
        String message = super.getMessage(messageKey, def);

        // Escape targeting parameters
        String useTargetName = null;
        if (currentCast != null) {
            useTargetName = currentCast.getTargetName();
        }
        if (useTargetName == null) {
            Target target = targeting.getTarget();
            if (target != null) {
                if (target.hasEntity() && getTargetType() != TargetType.BLOCK) {
                    useTargetName = controller.getEntityDisplayName(target.getEntity());
                } else if (target.isValid() && getTargetType() != TargetType.OTHER_ENTITY && getTargetType() != TargetType.ANY_ENTITY) {
                    MaterialAndData material = target.getTargetedMaterial();
                    if (material != null)
                    {
                        useTargetName = material.getName();
                    }
                }
            }
        }
        if (useTargetName == null) {
            message = message.replace("$target", "Nothing");
        } else {
            message = message.replace("$target", useTargetName);
        }

        return message;
    }

    public boolean isReflective(Material mat)
    {
        return reflectiveMaterials != null && reflectiveMaterials.contains(mat);
    }

    public boolean isTargetable(CastContext context, Block block) {
        if (targetBreakables > 0 && context.isBreakable(block)) {
            return true;
        }
        if (!bypassBackfire && context.isReflective(block)) {
            return true;
        }
        return isTargetable(block.getType());
    }

    public boolean isTargetable(Material mat)
    {
        if (!allowPassThrough(mat)) {
            return true;
        }
        boolean targetThrough = targetThroughMaterials.contains(mat);
        if (reverseTargeting)
        {
            return(targetThrough);
        }
        if (!targetThrough && targetableMaterials != null)
        {
            return targetableMaterials.contains(mat);
        }
        return !targetThrough;
    }

    public void setReverseTargeting(boolean reverse)
    {
        reverseTargeting = reverse;
    }

    public void setTargetSpaceRequired()
    {
        targeting.setTargetSpaceRequired(true);
    }

    public void setTargetMinOffset(int offset) {
        targeting.setTargetMinOffset(offset);
    }

    public void setTarget(Location location) {
        targeting.targetBlock(getEyeLocation(), location == null ? null : location.getBlock());
    }

    public void setTargetingHeight(int offset) {
        targeting.setYOffset(offset);
    }

    public TargetType getTargetType()
    {
        return targeting.getTargetType();
    }

    public Block getPreviousBlock() {
        return targeting.getPreviousBlock();
    }

    public void retarget(int range, double fov, double closeRange, double closeFOV, boolean useHitbox, int yOffset, boolean targetSpaceRequired, int targetMinOffset) {
        initializeTargeting();
        this.range = range;
        targeting.setYOffset(yOffset);
        targeting.setTargetSpaceRequired(targetSpaceRequired);
        targeting.setTargetMinOffset(targetMinOffset);
        targeting.setFOV(fov);
        targeting.setCloseRange(closeFOV);
        targeting.setCloseFOV(closeRange);
        targeting.setUseHitbox(useHitbox);
        target();
    }

    public void retarget(int range, double fov, double closeRange, double closeFOV, boolean useHitbox) {
        initializeTargeting();
        this.range = range;
        targeting.setFOV(fov);
        targeting.setCloseRange(closeFOV);
        targeting.setCloseFOV(closeRange);
        targeting.setUseHitbox(useHitbox);
        target();
    }

    @Override
    public void target()
    {
        if (!targeting.hasTarget())
        {
            getTarget();
        }
    }

    protected Target processBlockEffects()
    {
        Target target = targeting.getTarget();
        Target originalTarget = target;
        final Block block = target.getBlock();
        Double backfireAmount = currentCast.getReflective(block);
        if (backfireAmount != null) {
            if (random.nextDouble() < backfireAmount) {
                final Entity mageEntity = mage.getEntity();
                final Location location = getLocation();
                final Location originLocation = block.getLocation();
                Vector direction = location.getDirection();
                originLocation.setDirection(direction.multiply(-1));
                this.location = originLocation;
                backfire();
                final Collection<com.elmakers.mine.bukkit.api.effect.EffectPlayer> effects = getEffects("cast");
                if (effects.size() > 0) {
                    Bukkit.getScheduler().runTaskLater(controller.getPlugin(),
                        new Runnable() {
                            @Override
                            public void run() {
                                for (com.elmakers.mine.bukkit.api.effect.EffectPlayer player : effects) {
                                    player.setMaterial(getEffectMaterial());
                                    player.setColor(mage.getEffectColor());
                                    player.start(originLocation, null, location, mageEntity);
                                }
                            }
                        }, 5l);
                }
                target = new Target(getEyeLocation(), mageEntity);
            }
        }

        if (targetBreakables > 0 && originalTarget.isValid() && block != null && currentCast.isBreakable(block)) {
            targeting.breakBlock(currentCast, block, targetBreakables);
        }

        return target;
    }

    protected Target findTarget()
    {
        Location source = getEyeLocation();
        TargetType targetType = targeting.getTargetType();
        boolean isBlock = targetType == TargetType.BLOCK || targetType == TargetType.SELECT;
        if (!isBlock && targetEntity != null) {
            return new Target(source, targetEntity);
        }

        if (targetType != TargetType.SELF && targetLocation != null) {
            return new Target(source, targetLocation.getBlock());
        }

        Target target = targeting.target(currentCast, getMaxRange());
        return targeting.getResult() == Targeting.TargetingResult.MISS && !allowMaxRange ? new Target(source) : target;
    }

    protected Target getTarget()
    {
        Target target = findTarget();

        if (instantBlockEffects)
        {
            target = processBlockEffects();
        }
        if (originAtTarget && target.isValid()) {
            Location previous = this.location;
            if (previous == null && mage != null) {
                previous = mage.getLocation();
            }
            location = target.getLocation().clone();
            if (previous != null) {
                location.setPitch(previous.getPitch());
                location.setYaw(previous.getYaw());
            }
        }

        if (currentCast != null)
        {
            Entity targetEntity = target != null ? target.getEntity() : null;
            Location targetLocation = target != null ? target.getLocation() : null;
            currentCast.setTargetLocation(targetLocation);
            currentCast.setTargetEntity(targetEntity);
        }

        return target;
    }

    public Target getCurrentTarget()
    {
        return targeting.getOrCreateTarget(getEyeLocation());
    }

    public Block getTargetBlock()
    {
        return getTarget().getBlock();
    }

    public List<Target> getAllTargetEntities() {
        // This target-clearing is a bit hacky, but this is only used when we want to reset
        // targeting.
        targeting.start(currentCast.getEyeLocation());
        return targeting.getAllTargetEntities(currentCast, this.getMaxRange());
    }

    @Override
    public boolean canTarget(Entity entity) {
        // This is mainly here to ignore pets...
        if (!targetUnknown && entity.getType() == EntityType.UNKNOWN) {
            return false;
        }
        if (entity.hasMetadata("notarget")) return false;
        if (!targetNPCs && controller.isNPC(entity)) return false;
        if (!targetArmorStands && entity instanceof ArmorStand) return false;
        if (damageResistanceProtection > 0 && entity instanceof LivingEntity)
        {
            LivingEntity living = (LivingEntity)entity;
            if (living.hasPotionEffect(PotionEffectType.DAMAGE_RESISTANCE)) {
                Collection<PotionEffect> effects = living.getActivePotionEffects();
                for (PotionEffect effect : effects) {
                    if (effect.getType().equals(PotionEffectType.DAMAGE_RESISTANCE) && effect.getAmplifier() >= damageResistanceProtection) {
                        return false;
                    }
                }
            }
        }
        if (entity instanceof Player)
        {
            Player player = (Player)entity;
            if (checkProtection && player.hasPermission("Magic.protected." + this.getKey())) return false;
            if (controller.isMage(entity) && isSuperProtected(controller.getMage(entity))) return false;
        }
        // Ignore invisible entities
        if (!targetInvisible && entity instanceof LivingEntity && ((LivingEntity)entity).hasPotionEffect(PotionEffectType.INVISIBILITY)) return false;

        if (targetContents != null && entity instanceof ItemFrame)
        {
            ItemFrame itemFrame = (ItemFrame)entity;
            ItemStack item = itemFrame.getItem();
            if (item == null || item.getType() != targetContents) return false;
        }
        if (targetEntityType == null && targetEntityTypes == null) return super.canTarget(entity);
        if (targetEntityTypes != null) {
            return targetEntityTypes.contains(entity.getType()) && super.canTarget(entity);
        }
        return targetEntityType.isAssignableFrom(entity.getClass()) && super.canTarget(entity);
    }

    public boolean isSuperProtected(Mage mage) {
        return !bypassProtection && mage.isSuperProtected();
    }

    protected int getMaxRange()
    {
        if (allowMaxRange) return Math.min(MAX_RANGE, range);
        float multiplier = (mage == null) ? 1 : mage.getRangeMultiplier();
        return Math.min(MAX_RANGE, (int)(multiplier * range));
    }

    @Override
    public int getRange()
    {
        TargetType targetType = targeting.getTargetType();
        if (targetType == TargetType.NONE || targetType == TargetType.SELF) return 0;
        return getMaxRange();
    }

    protected int getMaxRangeSquared()
    {
        int maxRange = getMaxRange();
        return maxRange * maxRange;
    }

    protected void setMaxRange(int range)
    {
        this.range = range;
    }

    public boolean isTransparent(Material material)
    {
        return targetThroughMaterials.contains(material);
    }


    public Block getInteractBlock() {
        Location location = getEyeLocation();
        if (location == null) return null;
        Block playerBlock = location.getBlock();
        if (isTargetable(playerBlock.getType())) return playerBlock;
        Vector direction = location.getDirection().normalize();
        return location.add(direction).getBlock();
    }

    public Block findBlockUnder(Block block)
    {
        int depth = 0;
        if (targetThroughMaterials.contains(block.getType()))
        {
            while (depth < verticalSearchDistance && targetThroughMaterials.contains(block.getType()))
            {
                depth++;
                block = block.getRelative(BlockFace.DOWN);
            }
        }
        else
        {
            while (depth < verticalSearchDistance && !targetThroughMaterials.contains(block.getType()))
            {
                depth++;
                block = block.getRelative(BlockFace.UP);
            }
            block = block.getRelative(BlockFace.DOWN);
        }

        return block;
    }

    public Block findSpaceAbove(Block block)
    {
        int depth = 0;
        while (depth < verticalSearchDistance && !targetThroughMaterials.contains(block.getType()))
        {
            depth++;
            block = block.getRelative(BlockFace.UP);
        }
        return block;
    }

    @Override
    protected void reset()
    {
        super.reset();
        this.initializeTargeting();
    }

    @SuppressWarnings("unchecked")
    protected void loadTemplate(ConfigurationSection node)
    {
        super.loadTemplate(node);

        // Preload some parameters that may appear in spell lore
        ConfigurationSection parameters = node.getConfigurationSection("parameters");
        if (parameters != null)
        {
            processTemplateParameters(parameters);
        }
    }

    protected void processTemplateParameters(ConfigurationSection parameters) {
        range = parameters.getInt("range", 0);
        boolean hasTargeting = parameters.contains("target");
        targeting.parseTargetType(parameters.getString("target"));

        // If a range was specified but not a target type, default to none
        if (range > 0 && !hasTargeting) {
            targeting.setTargetType(TargetType.OTHER);
        }
        TargetType targetType = targeting.getTargetType();

        // Use default range of 32 for configs that didn't specify range
        // Only when targeting is set to on
        if ((targetType != TargetType.NONE && targetType != TargetType.SELF) && range == 0) {
            range = 32;
        }

        // Re-process targetSelf parameter, defaults to on if targetType is "self"
        targetSelf = (targetType == TargetType.SELF);
        targetSelf = parameters.getBoolean("target_self", targetSelf);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void processParameters(ConfigurationSection parameters) {
        super.processParameters(parameters);
        targeting.processParameters(parameters);
        processTemplateParameters(parameters);
        allowMaxRange = parameters.getBoolean("allow_max_range", false);
        bypassBackfire = parameters.getBoolean("bypass_backfire", false);
        checkProtection = parameters.getBoolean("check_protection", false);
        damageResistanceProtection = parameters.getInt("damage_resistance_protection", 0);
        targetBreakables = parameters.getDouble("target_breakables", 1);
        reverseTargeting = parameters.getBoolean("reverse_targeting", false);
        instantBlockEffects = parameters.getBoolean("instant_block_effects", false);

        if (parameters.contains("transparent")) {
            targetThroughMaterials.clear();
            targetThroughMaterials.addAll(controller.getMaterialSet(parameters.getString("transparent")));
        } else {
            targetThroughMaterials.clear();
            targetThroughMaterials.addAll(controller.getMaterialSet("transparent"));
        }

        if (parameters.contains("targetable")) {
            targetableMaterials = new HashSet<Material>();
            targetableMaterials.addAll(controller.getMaterialSet(parameters.getString("targetable")));
        } else {
            targetableMaterials = null;
        }

        reflectiveMaterials = null;
        if (parameters.contains("reflective")) {
            reflectiveMaterials = controller.getMaterialSet(parameters.getString("reflective"));
        }

        if (parameters.getBoolean("reflective_override", true)) {
            String reflectiveKey = controller.getReflectiveMaterials(mage, mage.getLocation());
            if (reflectiveKey != null) {
                Set<Material> currentReflective = reflectiveMaterials;
                reflectiveMaterials = controller.getMaterialSet(reflectiveKey);
                if (currentReflective != null) {
                    reflectiveMaterials = new HashSet<Material>(reflectiveMaterials);
                    reflectiveMaterials.addAll(currentReflective);
                }
            }
        }

        targetNPCs = parameters.getBoolean("target_npc", false);
        targetArmorStands = parameters.getBoolean("target_armor_stand", false);
        targetInvisible = parameters.getBoolean("target_invisible", true);
        targetUnknown = parameters.getBoolean("target_unknown", true);

        if (parameters.contains("target_type")) {
            String entityTypeName = parameters.getString("target_type");
            try {
                 Class<?> typeClass = Class.forName("org.bukkit.entity." + entityTypeName);
                 if (Entity.class.isAssignableFrom(typeClass)) {
                     targetEntityType = (Class<? extends Entity>)typeClass;
                 } else {
                     controller.getLogger().warning("Entity type: " + entityTypeName + " not assignable to Entity");
                 }
            } catch (Throwable ex) {
                controller.getLogger().warning("Unknown entity type in target_type of " + getKey() + ": " + entityTypeName);
                targetEntityType = null;
            }
        } else if (parameters.contains("target_types")) {
            targetEntityType = null;
            targetEntityTypes = new HashSet<EntityType>();
            Collection<String> typeKeys = ConfigurationUtils.getStringList(parameters, "target_types");
            for (String typeKey : typeKeys) {
                try {
                    EntityType entityType = EntityType.valueOf(typeKey.toUpperCase());
                    if (entityType == null) {
                        throw new Exception("Bad entity type");
                    }
                    targetEntityTypes.add(entityType);
                } catch (Throwable ex) {
                    controller.getLogger().warning("Unknown entity type in target_types of " + getKey() + ": " + typeKey);
                }
            }
        } else {
            targetEntityType = null;
            targetEntityTypes = null;
        }

        targetContents = ConfigurationUtils.getMaterial(parameters, "target_contents", null);
        originAtTarget = parameters.getBoolean("origin_at_target", false);

        Location defaultLocation = getLocation();
        targetLocation = ConfigurationUtils.overrideLocation(parameters, "t", defaultLocation, controller.canCreateWorlds());

        // For two-click construction spells
        defaultLocation = targetLocation == null ? defaultLocation : targetLocation;
        targetLocation2 = ConfigurationUtils.overrideLocation(parameters, "t2", defaultLocation, controller.canCreateWorlds());

        if (parameters.contains("player")) {
            Player player = controller.getPlugin().getServer().getPlayer(parameters.getString("player"));
            if (player != null) {
                targetLocation = player.getLocation();
                targetEntity = player;
            }
        } else {
            targetEntity = null;
        }

        // Special hack that should work well in most casts.
        if (isUnderwater()) {
            targetThroughMaterials.add(Material.WATER);
            targetThroughMaterials.add(Material.STATIONARY_WATER);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected String getDisplayMaterialName()
    {
        Target target = targeting.getTarget();
        if (target != null && target.isValid()) {
            return MaterialBrush.getMaterialName(target.getBlock());
        }

        return super.getDisplayMaterialName();
    }

    @Override
    protected void onBackfire() {
        targeting.setTargetType(TargetType.SELF);
    }

    @Override
    public Location getTargetLocation() {
        Target target = targeting.getTarget();
        if (target != null && target.isValid()) {
            return target.getLocation();
        }

        return null;
    }

    @Override
    public Entity getTargetEntity() {
        Target target = targeting.getTarget();
        if (target != null && target.isValid()) {
            return target.getEntity();
        }

        return null;
    }

    @Override
    public com.elmakers.mine.bukkit.api.block.MaterialAndData getEffectMaterial()
    {
        Target target = targeting.getTarget();
        if (target != null && target.isValid()) {
            Block block = target.getBlock();
            MaterialAndData targetMaterial = new MaterialAndData(block);
            if (targetMaterial.getMaterial() == Material.AIR) {
                targetMaterial.setMaterial(DEFAULT_EFFECT_MATERIAL);
            }
            return targetMaterial;
        }
        return super.getEffectMaterial();
    }

    public Class<? extends Entity> getTargetEntityType() {
        return targetEntityType;
    }
}