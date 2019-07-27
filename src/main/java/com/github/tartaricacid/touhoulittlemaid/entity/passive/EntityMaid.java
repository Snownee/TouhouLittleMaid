package com.github.tartaricacid.touhoulittlemaid.entity.passive;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.AbstractEntityMaid;
import com.github.tartaricacid.touhoulittlemaid.api.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidAPI;
import com.github.tartaricacid.touhoulittlemaid.api.MaidInventory;
import com.github.tartaricacid.touhoulittlemaid.api.util.BaubleItemHandler;
import com.github.tartaricacid.touhoulittlemaid.config.GeneralConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.*;
import com.github.tartaricacid.touhoulittlemaid.init.MaidBlocks;
import com.github.tartaricacid.touhoulittlemaid.init.MaidItems;
import com.github.tartaricacid.touhoulittlemaid.init.MaidSoundEvent;
import com.github.tartaricacid.touhoulittlemaid.internal.task.TaskIdle;
import com.github.tartaricacid.touhoulittlemaid.item.ItemKappaCompass;
import com.github.tartaricacid.touhoulittlemaid.proxy.ClientProxy;
import com.github.tartaricacid.touhoulittlemaid.proxy.CommonProxy;
import com.github.tartaricacid.touhoulittlemaid.util.ParseI18n;
import com.google.common.base.Predicate;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.*;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.passive.EntityOcelot;
import net.minecraft.entity.passive.EntityParrot;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Enchantments;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.InventoryHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.pathfinding.PathNavigateGround;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.World;
import net.minecraftforge.common.IShearable;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import net.minecraftforge.items.*;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import net.minecraftforge.items.wrapper.EntityArmorInvWrapper;
import net.minecraftforge.items.wrapper.EntityHandsInvWrapper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

public class EntityMaid extends AbstractEntityMaid {
    public static final Predicate<Entity> IS_PICKUP = entity -> (entity instanceof EntityItem || entity instanceof EntityXPOrb || entity instanceof EntityArrow);
    public static final Predicate<Entity> IS_MOB = entity -> entity instanceof EntityMob;
    public static final Predicate<Entity> CAN_SHEAR = entity -> entity instanceof IShearable && ((IShearable) entity).isShearable(new ItemStack(Items.SHEARS), entity.world, entity.getPosition());

    private static final DataParameter<Boolean> BEGGING = EntityDataManager.createKey(EntityMaid.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> PICKUP = EntityDataManager.createKey(EntityMaid.class, DataSerializers.BOOLEAN);
    private static final DataParameter<String> TASK = EntityDataManager.createKey(EntityMaid.class, DataSerializers.STRING);
    private static final DataParameter<Integer> EXP = EntityDataManager.createKey(EntityMaid.class, DataSerializers.VARINT);
    private static final DataParameter<BlockPos> HOME_POS = EntityDataManager.createKey(EntityMaid.class, DataSerializers.BLOCK_POS);
    private static final DataParameter<Boolean> HOME = EntityDataManager.createKey(EntityMaid.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> ARM_RISE = EntityDataManager.createKey(EntityMaid.class, DataSerializers.BOOLEAN);
    private static final DataParameter<String> MODEL_ID = EntityDataManager.createKey(EntityMaid.class, DataSerializers.STRING);

    private final EntityArmorInvWrapper armorInvWrapper = new EntityArmorInvWrapper(this);
    private final EntityHandsInvWrapper handsInvWrapper = new EntityHandsInvWrapper(this);
    private final ItemStackHandler mainInv = new ItemStackHandler(15);
    private final BaubleItemHandler baubleInv = new BaubleItemHandler(8);

    /**
     * 拾起物品声音的延时计数器
     */
    private static int pickupSoundCount = 60;
    /**
     * 玩家伤害女仆后的声音延时计数器
     */
    private static int playerHurtSoundCount = 100;

    /**
     * 依据此变量，在打开 GUI 时暂时中断实体的 AI 执行
     */
    public boolean guiOpening;
    /**
     * 用来暂存当前实体所调用的 IMaidTask 对象
     */
    @Nonnull
    private IMaidTask task = LittleMaidAPI.getIdleTask();
    /**
     * 当前 IMaidTask 对象对应的 AI
     */
    @Nullable
    private EntityAIBase taskAI;

    public EntityMaid(World worldIn) {
        super(worldIn);
        setSize(0.6f, 1.5f);
    }

    @Override
    protected void initEntityAI() {
        this.tasks.addTask(1, new EntityAISwimming(this));
        this.tasks.addTask(2, new EntityMaidSit(this));
        this.tasks.addTask(3, new EntityMaidPanic(this, 1.0f));
        this.tasks.addTask(3, new EntityMaidReturnHome(this, 0.6f, 200));
        this.tasks.addTask(4, new EntityMaidBeg(this, 8.0f));

        this.tasks.addTask(6, new EntityMaidPickup(this, 0.8f));
        this.tasks.addTask(6, new EntityMaidFollowOwner(this, 0.8f, 6.0F, 2.0F));

        this.tasks.addTask(8, new EntityAIOpenDoor(this, true));
        this.tasks.addTask(9, new EntityAIWatchClosest2(this, EntityPlayer.class, 6.0F, 0.2f));
        this.tasks.addTask(9, new EntityAIWatchClosest(this, EntityWolf.class, 6.0F, 0.2f));
        this.tasks.addTask(9, new EntityAIWatchClosest(this, EntityOcelot.class, 6.0F, 0.2f));
        this.tasks.addTask(9, new EntityAIWatchClosest(this, EntityParrot.class, 6.0F, 0.2f));
        this.tasks.addTask(10, new EntityAILookIdle(this));
        this.tasks.addTask(11, new EntityMaidWanderAvoidWater(this, 0.4f));

        this.targetTasks.addTask(1, new EntityAIHurtByTarget(this, true));
        this.targetTasks.addTask(2, new EntityAINearestAttackableTarget(this, EntityMob.class, true));
        this.targetTasks.addTask(3, new EntityAIOwnerHurtByTarget(this));
        this.targetTasks.addTask(4, new EntityAIOwnerHurtTarget(this));
    }

    @Override
    protected PathNavigate createNavigator(World worldIn) {
        PathNavigateGround pathNavigate = new PathNavigateGround(this, worldIn);
        pathNavigate.setBreakDoors(true);
        return pathNavigate;
    }

    @Override
    protected void entityInit() {
        super.entityInit();
        this.dataManager.register(BEGGING, Boolean.FALSE);
        this.dataManager.register(PICKUP, Boolean.TRUE);
        this.dataManager.register(TASK, TaskIdle.UID.toString());
        this.dataManager.register(EXP, 0);
        this.dataManager.register(HOME_POS, BlockPos.ORIGIN);
        this.dataManager.register(HOME, Boolean.FALSE);
        this.dataManager.register(ARM_RISE, Boolean.FALSE);
        this.dataManager.register(MODEL_ID, "touhou_little_maid:hakurei_reimu");
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        this.getAttributeMap().registerAttribute(SharedMonsterAttributes.ATTACK_DAMAGE);
        this.getAttributeMap().getAttributeInstance(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.4d);
    }

    @Override
    public void onLivingUpdate() {
        baubleInv.fireEvent((b, s) -> {
            b.onTick(this, s);
            return false;
        });
        // 更新手部使用
        this.updateArmSwingProgress();
        // 计数器自减
        if (pickupSoundCount > 0) {
            pickupSoundCount--;
        }
        if (playerHurtSoundCount > 0) {
            playerHurtSoundCount--;
        }
        // 随机回血
        this.randomRestoreHealth();
        super.onLivingUpdate();
    }

    /**
     * 随机拥有小概率回血
     * <p>
     * 每 tick 有 0.25% 概率回血，也就是平均 20 秒尝试回血一次
     */
    private void randomRestoreHealth() {
        if (this.getHealth() <= this.getMaxHealth() - 1 && rand.nextFloat() < 0.0025) {
            this.heal(1);
            this.spawnRestoreHealthParticle(rand.nextInt(3) + 7);
        }
    }

    /**
     * 来自原版爆炸粒子效果的修改
     */
    private void spawnRestoreHealthParticle(int particleCount) {
        if (this.world.isRemote) {
            for (int i = 0; i < particleCount; ++i) {
                double d0 = this.rand.nextGaussian() * 0.02D;
                double d1 = this.rand.nextGaussian() * 0.02D;
                double d2 = this.rand.nextGaussian() * 0.02D;
                this.world.spawnParticle(EnumParticleTypes.SPELL,
                        this.posX + (double) (this.rand.nextFloat() * this.width * 2.0F) - (double) this.width - d0 * 10.0D,
                        this.posY + (double) (this.rand.nextFloat() * this.height) - d1 * 10.0D,
                        this.posZ + (double) (this.rand.nextFloat() * this.width * 2.0F) - (double) this.width - d2 * 10.0D,
                        d0, d1, d2);
            }
        }
    }

    @Override
    protected void collideWithNearbyEntities() {
        super.collideWithNearbyEntities();

        // 先判断拾物模式是否开启，没有开启的话，什么都不会吸收
        if (!this.isPickup()) {
            return;
        }

        List<Entity> entityList = this.world.getEntitiesInAABBexcluding(this,
                this.getEntityBoundingBox().expand(0.5, 0, 0.5).expand(-0.5, 0, -0.5), IS_PICKUP);
        if (!entityList.isEmpty() && this.isEntityAlive()) {
            for (Entity entityPickup : entityList) {
                // 如果是物品
                if (entityPickup instanceof EntityItem) {
                    pickupItem((EntityItem) entityPickup, false);
                }
                // 如果是经验
                if (entityPickup instanceof EntityXPOrb) {
                    pickupXPOrb((EntityXPOrb) entityPickup);
                }
                // 如果是箭
                if (entityPickup instanceof EntityArrow) {
                    pickupArrow((EntityArrow) entityPickup, false);
                }
            }
        }
    }

    /**
     * 捡起物品部分的逻辑
     *
     * @param simulate 是否是模拟塞入，可用于检测此物品是否能拾起
     */
    public boolean pickupItem(EntityItem entityItem, boolean simulate) {
        // TODO: 当物品 pickupDelay 较小时等待
        if (!world.isRemote && entityItem.isEntityAlive() && !entityItem.cannotPickup()) {
            // 获取实体的物品堆，遍历尝试塞入背包
            ItemStack itemstack = entityItem.getItem();
            // 获取数量，为后面方面用
            int count = itemstack.getCount();
            itemstack = ItemHandlerHelper.insertItemStacked(getAvailableInv(false), itemstack, simulate);
            if (count == itemstack.getCount()) {
                return false;
            }
            if (!simulate) {
                // 这是向客户端同步数据用的，如果加了这个方法，会有短暂的拾取动画和音效
                this.onItemPickup(entityItem, count - itemstack.getCount());
                if (pickupSoundCount == 0) {
                    this.playSound(MaidSoundEvent.MAID_ITEM_GET, 1, 1);
                    pickupSoundCount = 60;
                }
                // 如果遍历塞完后发现为空了
                if (itemstack.isEmpty()) {
                    // 清除这个实体
                    entityItem.setDead();
                } else {
                    // 将物品数量同步到客户端
                    entityItem.setItem(itemstack);
                }
            }

            return true;
        }
        return false;
    }

    /**
     * 捡起经验球部分的逻辑
     */
    private void pickupXPOrb(EntityXPOrb entityXPOrb) {
        if (!this.world.isRemote && entityXPOrb.isEntityAlive() && entityXPOrb.delayBeforeCanPickup == 0) {
            // 这是向客户端同步数据用的，如果加了这个方法，会有短暂的拾取动画和音效
            this.onItemPickup(entityXPOrb, 1);
            if (pickupSoundCount == 0) {
                this.playSound(MaidSoundEvent.MAID_ITEM_GET, 1, 1);
                pickupSoundCount = 60;
            }

            // 对经验修补的应用，因为全部来自于原版，所以效果也是相同的
            ItemStack itemstack = EnchantmentHelper.getEnchantedItem(Enchantments.MENDING, this);
            if (!itemstack.isEmpty() && itemstack.isItemDamaged()) {
                int i = Math.min(entityXPOrb.xpValue * 2, itemstack.getItemDamage());
                entityXPOrb.xpValue -= (i / 2);
                itemstack.setItemDamage(itemstack.getItemDamage() - i);
            }
            if (entityXPOrb.xpValue > 0) {
                this.addExp(entityXPOrb.xpValue);
            }
            entityXPOrb.setDead();
        }
    }

    /**
     * 捡起箭部分的逻辑
     *
     * @param simulate 是否是模拟塞入，可用于检测此物品是否能拾起
     */
    public boolean pickupArrow(EntityArrow arrow, boolean simulate) {
        if (!this.world.isRemote && arrow.isEntityAlive() && arrow.inGround && arrow.arrowShake <= 0) {
            // 先判断箭是否处于可以拾起的状态
            if (arrow.pickupStatus != EntityArrow.PickupStatus.ALLOWED) {
                return false;
            }
            // 能够塞入
            ItemStack stack = getArrowFromEntity(arrow);
            if (!ItemHandlerHelper.insertItemStacked(getAvailableInv(false), stack, simulate).isEmpty()) {
                return false;
            }
            // 非模拟状态下，清除实体箭
            if (!simulate) {
                // 这是向客户端同步数据用的，如果加了这个方法，会有短暂的拾取动画和音效
                this.onItemPickup(arrow, 1);
                if (pickupSoundCount == 0) {
                    this.playSound(MaidSoundEvent.MAID_ITEM_GET, 1, 1);
                    pickupSoundCount = 60;
                }
                arrow.setDead();
            }
            return true;
        }
        return false;
    }

    /**
     * 无法对抽象类使用 AT，所以通过反射获取箭
     */
    public ItemStack getArrowFromEntity(EntityArrow entity) {
        Method method = ReflectionHelper.findMethod(entity.getClass(), "getArrowStack", "func_184550_j");
        method.setAccessible(true);
        try {
            return (ItemStack) method.invoke(ItemStack.class);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            return new ItemStack(Items.ARROW);
        }
    }

    @Override
    public void attackEntityWithRangedAttack(EntityLivingBase target, float distanceFactor) {
        task.onRangedAttack(this, target, distanceFactor);
    }

    @Override
    public boolean attackEntityAsMob(@Nonnull Entity entityIn) {
        // 先获取实体基本的攻击数据
        float damage = (float) this.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue();
        // 用来获取击退相关数据
        int knockBack = 0;
        // 火焰附加
        int fireAspect = 0;

        if (entityIn instanceof EntityLivingBase) {
            // 附加上主手武器的攻击（含附魔）数据
            damage += EnchantmentHelper.getModifierForCreature(this.getHeldItemMainhand(), ((EntityLivingBase) entityIn).getCreatureAttribute());
            // 附加上击退附魔数据
            knockBack += EnchantmentHelper.getKnockbackModifier(this);
            // 附加上火焰附加带来的数据
            fireAspect += EnchantmentHelper.getFireAspectModifier(this);
        }

        // 检查攻击对象是否是无敌的
        boolean isInvulnerable = entityIn.attackEntityFrom(DamageSource.causeMobDamage(this), damage);

        // 如果不是无敌的
        if (isInvulnerable) {
            // 应用击退效果
            if (knockBack > 0) {
                ((EntityLivingBase) entityIn).knockBack(this, knockBack * 0.5F,
                        MathHelper.sin(this.rotationYaw * 0.017453292F),
                        (-MathHelper.cos(this.rotationYaw * 0.017453292F)));
                this.motionX *= 0.6D;
                this.motionZ *= 0.6D;
            }

            // 应用火焰附加效果
            if (fireAspect > 0) {
                entityIn.setFire(fireAspect * 4);
            }

            // 如果攻击对象是玩家
            if (entityIn instanceof EntityPlayer) {
                attackEntityAsPlayer((EntityPlayer) entityIn);
            }

            // 应用其他附魔
            this.applyEnchantments(this, entityIn);
            // 别忘了扣除耐久
            this.getHeldItemMainhand().damageItem(1, this);
        }

        return isInvulnerable;
    }

    /**
     * 攻击玩家
     */
    private void attackEntityAsPlayer(EntityPlayer entityplayer) {
        // 攻击方手持的物品
        ItemStack itemMaidHand = this.getHeldItemMainhand();
        // 玩家手持物品
        ItemStack itemPlayerHand = entityplayer.isHandActive() ? entityplayer.getActiveItemStack() : ItemStack.EMPTY;

        // 如果玩家手持盾牌而且还处于持盾状态，并且所持物品能够破盾
        if (!itemMaidHand.isEmpty() && !itemPlayerHand.isEmpty() && itemMaidHand.getItem().canDisableShield(itemMaidHand, itemPlayerHand, entityplayer, this)
                && itemPlayerHand.getItem().isShield(itemPlayerHand, entityplayer)) {
            float efficiencyModifier = 0.25F + EnchantmentHelper.getEfficiencyModifier(this) * 0.05F;

            if (this.rand.nextFloat() < efficiencyModifier) {
                entityplayer.getCooldownTracker().setCooldown(itemPlayerHand.getItem(), 100);
                this.world.setEntityState(entityplayer, (byte) 30);
            }
        }
    }

    @Override
    protected void damageArmor(float damage) {
        // 依据原版玩家护甲耐久掉落机制书写而成
        damage = damage / 4.0F;

        // 最小伤害必须为 1.0
        if (damage < 1.0F) {
            damage = 1.0F;
        }

        for (int i = 0; i < this.armorInvWrapper.getSlots(); ++i) {
            ItemStack itemstack = this.armorInvWrapper.getStackInSlot(i);
            if (itemstack.getItem() instanceof ItemArmor) {
                itemstack.damageItem((int) damage, this);
            }
        }
    }

    @Nullable
    @Override
    public EntityAgeable createChild(@Nonnull EntityAgeable ageable) {
        return null;
    }

    @Override
    public boolean processInteract(EntityPlayer player, @Nullable EnumHand hand) {
        // 必须是主手
        if (hand == EnumHand.MAIN_HAND) {
            ItemStack itemstack = player.getHeldItem(hand);
            // 利用短路原理，逐个触发对应的交互事件
            return tamedMaid(itemstack, player) || writeHomePos(itemstack, player) || openGuiAndSitting(itemstack, player);
        }
        return false;
    }

    /**
     * 驯服女仆
     *
     * @return 该逻辑是否成功应用
     */
    private boolean tamedMaid(ItemStack itemstack, EntityPlayer player) {
        Item tamedItem = Item.getByNameOrId(GeneralConfig.MAID_CONFIG.maidTamedItem) == null ? Items.CAKE : Item.getByNameOrId(GeneralConfig.MAID_CONFIG.maidTamedItem);
        if (!this.isTamed() && itemstack.getItem() == tamedItem) {
            if (!world.isRemote) {
                consumeItemFromStack(player, itemstack);
                this.setTamedBy(player);
                this.playTameEffect(true);
                this.getNavigator().clearPath();
                this.world.setEntityState(this, (byte) 7);
                this.playSound(MaidSoundEvent.MAID_TAMED, 1, 1);
                return true;
            }
        }
        return false;
    }

    /**
     * 对女仆应用坐标
     *
     * @return 该逻辑是否成功应用
     */
    private boolean writeHomePos(ItemStack itemstack, EntityPlayer player) {
        if (this.isTamed() && this.getOwnerId().equals(player.getUniqueID()) && itemstack.getItem() == MaidItems.KAPPA_COMPASS) {
            BlockPos pos = ItemKappaCompass.getPos(itemstack);
            if (pos != null) {
                this.setHomePos(pos);
                if (!world.isRemote) {
                    // 尝试移动到这里，距离超过 16 就传送
                    // 没办法，路径系统最大只允许寻路 16
                    if (this.getPosition().distanceSq(pos) < 256) {
                        this.getNavigator().tryMoveToXYZ(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.6f);
                    } else {
                        this.attemptTeleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                    }
                    player.sendMessage(new TextComponentTranslation("message.touhou_little_maid.kappa_compass.write_success"));
                }
                return true;
            }
            if (!world.isRemote) {
                player.sendMessage(new TextComponentTranslation("message.touhou_little_maid.kappa_compass.write_fail"));
            }
        }
        return false;
    }

    /**
     * 打开 GUI 或者切换待命模式
     *
     * @return 该逻辑是否成功应用
     */
    private boolean openGuiAndSitting(ItemStack itemstack, EntityPlayer player) {
        if (this.isTamed() && this.getOwnerId() != null && this.getOwnerId().equals(player.getUniqueID())) {
            // 先清除寻路逻辑
            this.getNavigator().clearPath();
            // 如果玩家为潜行状态，那么切换待命
            if (player.isSneaking()) {
                if (this.isSitting()) {
                    this.setSitting(false);
                } else {
                    this.setRevengeTarget(null);
                    this.setSitting(true);
                }
                this.playSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.2F,
                        ((world.rand.nextFloat() - world.rand.nextFloat()) * 0.7F + 1.0F) * 2.0F);
            } else if (!world.isRemote) {
                // 否则打开 GUI
                // FIXME: 2019/7/24 这一块用了数字，最好能用枚举，而且和前面的 GUI 绘制部分统一起来
                player.openGui(TouhouLittleMaid.INSTANCE, 1, world, this.getEntityId(), LittleMaidAPI.getTasks().indexOf(task), 0);
            }
            return true;
        }
        return false;
    }

    /**
     * 女仆可不能繁殖哦
     */
    @Override
    public boolean isBreedingItem(ItemStack stack) {
        return false;
    }

    @Override
    public void onDeath(DamageSource cause) {
        super.onDeath(cause);

        // 防止 Forge 的事件系统的取消，导致后面掉落物的触发，故加此判定
        if (!dead) {
            return;
        }

        // 将女仆身上的物品进行掉落
        if (!world.isRemote) {
            IItemHandler itemHandler = this.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
            if (itemHandler != null) {
                for (int i = 0; i < itemHandler.getSlots(); ++i) {
                    ItemStack itemstack = itemHandler.getStackInSlot(i);
                    if (!itemstack.isEmpty()) {
                        InventoryHelper.spawnItemStack(world, this.posX, this.posY, this.posZ, itemstack);
                    }
                }
            }
            // 最后掉落手办
            dropGarageKit();
        }
    }

    /**
     * 掉落手办
     */
    private void dropGarageKit() {
        // 先在死亡前获取女仆的 NBT 数据
        NBTTagCompound entityTag = new NBTTagCompound();
        this.writeEntityToNBT(entityTag);
        // 剔除物品部分
        entityTag.removeTag("ArmorItems");
        entityTag.removeTag("HandItems");
        entityTag.removeTag(NBT.MAID_INVENTORY.getName());
        entityTag.removeTag(NBT.BAUBLE_INVENTORY.getName());
        // 掉落女仆手办
        ItemStack stack = MaidBlocks.GARAGE_KIT.getItemStackWithData("touhou_little_maid:entity.passive.maid",
                this.getModelId(), entityTag);
        // 生成物品实体
        entityDropItem(stack, 0);
    }

    @Override
    protected void dropEquipment(boolean wasRecentlyHit, int lootingModifier) {
        // 不要调用父类的掉落方法，很坑爹的会掉落耐久损失很多的东西
    }

    @Override
    @Deprecated
    public String getName() {
        if (this.hasCustomName()) {
            return this.getCustomNameTag();
        } else {
            String key = getModelId();
            if (world.isRemote) {
                if (ClientProxy.ID_INFO_MAP.containsKey(key)) {
                    return ParseI18n.parse(ClientProxy.ID_INFO_MAP.get(key).getName());
                }
            } else {
                if (CommonProxy.VANILLA_ID_NAME_MAP.containsKey(key)) {
                    return CommonProxy.VANILLA_ID_NAME_MAP.get(key);
                }
            }
            return super.getName();
        }
    }

    /**
     * 用于刷怪蛋、刷怪笼、自然生成的初始化
     */
    @Nullable
    @Override
    public IEntityLivingData onInitialSpawn(DifficultyInstance difficulty, @Nullable IEntityLivingData livingdata) {
        if (!CommonProxy.VANILLA_ID_NAME_MAP.isEmpty()) {
            // 随机获取某个模型对象
            String key = CommonProxy.VANILLA_ID_NAME_MAP.keySet().stream().skip(rand.nextInt(CommonProxy.VANILLA_ID_NAME_MAP.size())).findFirst().get();
            // 应用各种数据
            this.setModelId(key);
        }
        return super.onInitialSpawn(difficulty, livingdata);
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound compound) {
        super.readEntityFromNBT(compound);
        if (compound.hasKey(NBT.MAID_INVENTORY.getName())) {
            mainInv.deserializeNBT((NBTTagCompound) compound.getTag(NBT.MAID_INVENTORY.getName()));
        }
        if (compound.hasKey(NBT.BAUBLE_INVENTORY.getName())) {
            baubleInv.deserializeNBT((NBTTagCompound) compound.getTag(NBT.BAUBLE_INVENTORY.getName()));
        }
        if (compound.hasKey(NBT.IS_PICKUP.getName())) {
            setPickup(compound.getBoolean(NBT.IS_PICKUP.getName()));
        }
        if (compound.hasKey(NBT.MAID_TASK.getName())) {
            setTask(LittleMaidAPI.findTask(new ResourceLocation(compound.getString(NBT.MAID_TASK.getName())))
                    .or(LittleMaidAPI.getIdleTask()));
        }
        if (compound.hasKey(NBT.MAID_EXP.getName())) {
            setExp(compound.getInteger(NBT.MAID_EXP.getName()));
        }
        if (compound.hasKey(NBT.HOME_POS.getName())) {
            int[] pos = compound.getIntArray(NBT.HOME_POS.getName());
            setHomePos(new BlockPos(pos[0], pos[1], pos[2]));
        }
        if (compound.hasKey(NBT.MAID_HOME.getName())) {
            setHome(compound.getBoolean(NBT.MAID_HOME.getName()));
        }
        if (compound.hasKey(NBT.MODEL_ID.getName())) {
            setModelId(compound.getString(NBT.MODEL_ID.getName()));
        }
    }

    @Override
    public void writeEntityToNBT(NBTTagCompound compound) {
        super.writeEntityToNBT(compound);
        compound.setTag(NBT.MAID_INVENTORY.getName(), mainInv.serializeNBT());
        compound.setTag(NBT.BAUBLE_INVENTORY.getName(), baubleInv.serializeNBT());
        compound.setBoolean(NBT.IS_PICKUP.getName(), isPickup());
        compound.setString(NBT.MAID_TASK.getName(), task.getUid().toString());
        compound.setInteger(NBT.MAID_EXP.getName(), getExp());
        compound.setIntArray(NBT.HOME_POS.getName(), new int[]{getHomePos().getX(), getHomePos().getY(), getHomePos().getZ()});
        compound.setBoolean(NBT.MAID_HOME.getName(), isHome());
        compound.setString(NBT.MODEL_ID.getName(), getModelId());
    }

    @Override
    public int getTalkInterval() {
        return GeneralConfig.MAID_CONFIG.maidTalkInterval;
    }

    @Nullable
    @Override
    protected SoundEvent getAmbientSound() {
        return task.getAmbientSound(this);
    }

    @Nullable
    @Override
    protected SoundEvent getHurtSound(DamageSource damageSourceIn) {
        if (damageSourceIn.isFireDamage()) {
            return MaidSoundEvent.MAID_HURT_FIRE;
        } else if (damageSourceIn.getTrueSource() instanceof EntityPlayer) {
            if (playerHurtSoundCount == 0) {
                playerHurtSoundCount = 100;
                return MaidSoundEvent.MAID_PLAYER;
            } else {
                return null;
            }
        } else {
            return MaidSoundEvent.MAID_HURT;
        }
    }

    @Nullable
    @Override
    protected SoundEvent getDeathSound() {
        return MaidSoundEvent.MAID_DEATH;
    }

    @Nullable
    @Override
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(new CombinedInvWrapper(armorInvWrapper, handsInvWrapper, mainInv, baubleInv));
        } else {
            return super.getCapability(capability, facing);
        }
    }

    @Override
    public IItemHandlerModifiable getInv(MaidInventory type) {
        switch (type) {
            case MAIN:
                return mainInv;
            case HAND:
                return handsInvWrapper;
            case BAUBLE:
                return baubleInv;
            case ARMOR:
                return armorInvWrapper;
            default:
                return mainInv;
        }
    }

    @Override
    public BaubleItemHandler getBaubleInv() {
        return baubleInv;
    }

    @Override
    public CombinedInvWrapper getAvailableInv(boolean handsFirst) {
        return handsFirst ? new CombinedInvWrapper(handsInvWrapper, mainInv) : new CombinedInvWrapper(mainInv, handsInvWrapper);
    }

    public boolean isBegging() {
        return this.dataManager.get(BEGGING);
    }

    public void setBegging(boolean beg) {
        this.dataManager.set(BEGGING, beg);
    }

    @Override
    public boolean isPickup() {
        return this.dataManager.get(PICKUP);
    }

    public void setPickup(boolean pickup) {
        this.dataManager.set(PICKUP, pickup);
    }

    public IMaidTask getTask() {
        return LittleMaidAPI.findTask(new ResourceLocation(this.dataManager.get(TASK)))
                .or(LittleMaidAPI.getIdleTask());
    }

    public void setTask(IMaidTask task) {
        if (task == this.task) {
            return;
        }
        // 先应用 IMaidTask 对象对应的 AI
        if (!world.isRemote) {
            // 如果 taskAI 不为空，先将其移除
            if (this.taskAI != null) {
                tasks.removeTask(taskAI);
            }
            // 然后通过 IMaidTask 对象创建指定的 AI
            taskAI = task.createAI(this);
            // 再次检查此 AI 是否为空，加入 AI 列表中
            if (taskAI != null) {
                tasks.addTask(5, taskAI);
            }
        }
        // 将实体的 IMaidTask 对象指向传入的 IMaidTask
        this.task = task;
        // 往实体数据中存入此对象
        this.dataManager.set(TASK, task.getUid().toString());
    }

    public int getExp() {
        return this.dataManager.get(EXP);
    }

    public void setExp(int expIn) {
        this.dataManager.set(EXP, expIn);
    }

    public void addExp(int expIn) {
        setExp(getExp() + expIn);
    }

    public BlockPos getHomePos() {
        return this.dataManager.get(HOME_POS);
    }

    public void setHomePos(BlockPos home) {
        this.dataManager.set(HOME_POS, home);
    }

    @Override
    public boolean isHome() {
        return this.dataManager.get(HOME);
    }

    public void setHome(boolean isHome) {
        this.dataManager.set(HOME, isHome);
    }

    public boolean isSwingingArms() {
        return this.dataManager.get(ARM_RISE);
    }

    @Override
    public void setSwingingArms(boolean swingingArms) {
        this.dataManager.set(ARM_RISE, swingingArms);
    }

    public String getModelId() {
        return this.dataManager.get(MODEL_ID);
    }

    public void setModelId(String name) {
        this.dataManager.set(MODEL_ID, name);
    }

    @Override
    public boolean destroyBlock(BlockPos pos) {
        return world.destroyBlock(pos, true);
    }

    @Override
    public boolean placeBlock(BlockPos pos, IBlockState state) {
        return world.setBlockState(pos, state);
    }

    public enum NBT {
        // 女仆的物品栏
        MAID_INVENTORY("MaidInventory"),
        // 女仆饰品栏
        BAUBLE_INVENTORY("BaubleInventory"),
        // 能否捡起物品
        IS_PICKUP("IsPickup"),
        // 女仆模式
        MAID_TASK("MaidTask"),
        // 女仆经验
        MAID_EXP("MaidExp"),
        // Home 的坐标
        HOME_POS("HomePos"),
        // 是否开启 Home 模式
        MAID_HOME("MaidHome"),
        // 模型
        MODEL_ID("ModelId");

        private String name;

        /**
         * 在女仆存储时所使用的 NBT 标签名枚举
         *
         * @param name 存储的标签名
         */
        NBT(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }
}
