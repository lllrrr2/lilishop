package cn.lili.modules.order.order.serviceimpl;

import cn.lili.common.cache.Cache;
import cn.lili.common.cache.CachePrefix;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.rocketmq.RocketmqSendCallbackBuilder;
import cn.lili.common.rocketmq.tags.MqOrderTagsEnum;
import cn.lili.config.rocketmq.RocketmqCustomProperties;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.order.cart.entity.dto.MemberCouponDTO;
import cn.lili.modules.order.cart.entity.dto.TradeDTO;
import cn.lili.modules.order.cart.entity.vo.CartVO;
import cn.lili.modules.order.order.entity.dos.Order;
import cn.lili.modules.order.order.entity.dos.Trade;
import cn.lili.modules.order.order.entity.enums.PayStatusEnum;
import cn.lili.modules.order.order.mapper.TradeMapper;
import cn.lili.modules.order.order.service.OrderService;
import cn.lili.modules.order.order.service.TradeService;
import cn.lili.modules.promotion.service.CouponService;
import cn.lili.modules.promotion.service.MemberCouponService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 交易业务层实现
 *
 * @author Chopper
 * @date 2020/11/17 7:39 下午
 */
@Service
@Transactional
public class TradeServiceImpl extends ServiceImpl<TradeMapper, Trade> implements TradeService {

    //缓存
    @Autowired
    private Cache<Object> cache;
    //订单
    @Autowired
    private OrderService orderService;
    //会员
    @Autowired
    private MemberService memberService;
    //优惠券
    @Autowired
    private CouponService couponService;
    //会员优惠券
    @Autowired
    private MemberCouponService memberCouponService;
    //RocketMQ
    @Autowired
    private RocketMQTemplate rocketMQTemplate;
    //RocketMQ 配置
    @Autowired
    private RocketmqCustomProperties rocketmqCustomProperties;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Trade createTrade(TradeDTO tradeDTO) {
        Trade trade = new Trade(tradeDTO);
        String key = CachePrefix.TRADE.getPrefix() + trade.getSn();
        //积分预处理
        pointPretreatment(tradeDTO);
        //优惠券预处理
        couponPretreatment(tradeDTO);
        this.save(trade);
        orderService.intoDB(tradeDTO);
        //写入缓存，给消费者调用
        cache.put(key, tradeDTO);
        //构建订单创建消息
        String destination = rocketmqCustomProperties.getOrderTopic() + ":" + MqOrderTagsEnum.ORDER_CREATE.name();
        //发送订单创建消息
        rocketMQTemplate.asyncSend(destination, key, RocketmqSendCallbackBuilder.commonCallback());
        return trade;
    }

    @Override
    public Trade getBySn(String sn) {
        LambdaQueryWrapper<Trade> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Trade::getSn, sn);
        return this.getOne(queryWrapper);
    }


    @Override
    public void payTrade(String tradeSn, String paymentName, String receivableNo) {
        LambdaQueryWrapper<Order> orderQueryWrapper = new LambdaQueryWrapper<>();
        orderQueryWrapper.eq(Order::getTradeSn, tradeSn);
        List<Order> orders = orderService.list(orderQueryWrapper);
        for (Order order : orders) {
            orderService.payOrder(order.getSn(), paymentName, receivableNo);
        }
        Trade trade = this.getBySn(tradeSn);
        trade.setPayStatus(PayStatusEnum.PAID.name());
        this.saveOrUpdate(trade);
    }

    /**
     * 积分预处理
     * 下单同时，使用积分
     *
     * @param tradeDTO
     */
    private void pointPretreatment(TradeDTO tradeDTO) {
        StringBuilder orderSns = new StringBuilder();
        for (CartVO item : tradeDTO.getCartList()) {
            orderSns.append(item.getSn()).append(",");
        }
        if (tradeDTO.getPriceDetailDTO() != null && tradeDTO.getPriceDetailDTO().getPayPoint() != null && tradeDTO.getPriceDetailDTO().getPayPoint() > 0) {
            Member userInfo = memberService.getUserInfo();
            if (userInfo.getPoint() < tradeDTO.getPriceDetailDTO().getPayPoint()) {
                throw new ServiceException(ResultCode.PAY_POINT_ENOUGH);
            }
            boolean result = memberService.updateMemberPoint(tradeDTO.getPriceDetailDTO().
                            getPayPoint().longValue(), 0, tradeDTO.getMemberId(),
                    "订单【" + orderSns + "】创建，积分扣减");

            if (!result) {
                throw new ServiceException(ResultCode.PAY_POINT_ENOUGH);
            }
        }
    }


    /**
     * 优惠券预处理
     * 下单同时，扣除优惠券
     *
     * @param tradeDTO
     */
    private void couponPretreatment(TradeDTO tradeDTO) {
        List<MemberCouponDTO> memberCouponDTOList = new ArrayList<>();
        if (null != tradeDTO.getPlatformCoupon()) {
            memberCouponDTOList.add(tradeDTO.getPlatformCoupon());
        }
        Collection<MemberCouponDTO> storeCoupons = tradeDTO.getStoreCoupons().values();
        if (!storeCoupons.isEmpty()) {
            memberCouponDTOList.addAll(storeCoupons);
        }
        List<String> ids = memberCouponDTOList.stream().map(e -> e.getMemberCoupon().getId()).collect(Collectors.toList());
        memberCouponService.used(ids);
        memberCouponDTOList.forEach(e -> couponService.usedCoupon(e.getMemberCoupon().getCouponId(), 1));

    }


}