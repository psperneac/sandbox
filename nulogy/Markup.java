import org.junit.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Calculates product markups for packaging. Uses BigDecimals for prices.
 */
public class Markup {
	public static void main(String[] args) {

        Markup markup = new Markup();

        // initializes a context with the agents pipelines
        Context ctx = markup.init();

        // pushes some requests through the pipeline
        markup.push(ctx, "1299.99", 3, Type.FOOD);
        markup.push(ctx, "5432.00", 1, Type.PHARMA);
        markup.push(ctx, "12456.95", 4, Type.OTHER);
	}

    /**
     * creates a Product object, puts it in the context and calls execute on the agent
     */
    private void push(Context ctx, String price, int people, Type type) {
        Product p = new Product();
        p.price(price).people(people).type(type);

        // ctx = ctx.createSubContext();  // to create a per-request context, while still being able to look-up the agent pipeline
        ctx.put(Product.class, p);

        Agent agent = (Agent) ctx.get(Agent.class);
        try {
            agent.execute(ctx);
        } catch (AgentException e) {
            System.err.println(e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    @Test
    public void testPush() {
        Context ctx=init();
        push(ctx, "100", 2, Type.PHARMA);
        Product p = (Product) ctx.get(Product.class);

        // testing is push created a Product in context
        assertNotNull(p);

        //testing that the agent calculated the marked up price, though we won't test for a valid value at this point
        assertNotEquals(0.0, Double.parseDouble(p.markedUpPrice().toString()), 0.00001);
    }

    /**
     * Builds a context and the agent pipeline. This sort of approach is very good when extending the agent
     * pipeline without affecting current processing. You just make another agent; put it in the pipeline at the point.
     * In the new agent you look up parameters in the context, do processing, provide results in the context
     *
     * Ideas for other agents:
     * - A thread pool agent that would execute the pipeline on multiple threads. This works very well in this case
     *   as no state is being saved in the agents. For this you would need a hierarchical context where you can
     *   make a sub-context that inherits from the parent context but can also hold per-request values
     * - You can programatically insert Agents in-between all other agents in the pipeline that time the
     *   execution.
     * - You can insert Logging agents at any point the pipeline to log the contents of the Context for debugging.
     *
     * @return
     */
    private Context init() {
        Context ctx = new Context();

        ctx.put(Config.class, new Config());

        Agent output = new FormatCurrencyAgent(new PrintAgent());

        MultiTypeAgent agent = new MultiTypeAgent();
        agent.add(Type.ELECTRONICS, new ByTypeMarkupAgent(ctx, Type.ELECTRONICS, output));
        agent.add(Type.FOOD, new ByTypeMarkupAgent(ctx, Type.FOOD, output));
        agent.add(Type.PHARMA, new ByTypeMarkupAgent(ctx, Type.PHARMA, output));
        agent.add(Type.OTHER, new ByTypeMarkupAgent(ctx, Type.OTHER, output));

        ctx.put(Agent.class, new CheckContextAgent(new FlatMarkupAgent(new PeopleMarkupAgent(agent))));
        return ctx;
    }

    /**
     * This is overkill. Normally you wouldn't test if the whole setup is exactly the way you want. You can
     * just test that the entries in the context are the same type as they should and they are not null
     * and not care about the whole chain. Then forward to the integration testing to test that the chain behaves normally
     */
    @Test
    public void testContextInit() {
        Context ctx = init();
        assertNotNull(ctx);

        Config cfg=(Config) ctx.get(Config.class);
        assertNotNull(cfg);
        assertTrue(cfg instanceof Config);

        Agent check_agent = (Agent) ctx.get(Agent.class);
        assertNotNull(check_agent);
        assertTrue(check_agent instanceof Agent);
        assertTrue(check_agent instanceof ProxyAgent);
        assertTrue(check_agent instanceof CheckContextAgent);

        Agent flat_agent= ((CheckContextAgent) check_agent).getDelegate();
        assertNotNull(flat_agent);
        assertTrue(flat_agent instanceof Agent);
        assertTrue(flat_agent instanceof FlatMarkupAgent);
        assertTrue(flat_agent instanceof ProxyAgent);

        Agent people_agent = ((ProxyAgent)flat_agent).getDelegate();
        assertNotNull(people_agent);
        assertTrue(people_agent instanceof Agent);
        assertTrue(people_agent instanceof PeopleMarkupAgent);
        assertTrue(people_agent instanceof ProxyAgent);

        Agent multi=((ProxyAgent) people_agent).getDelegate();
        assertNotNull(multi);
        assertTrue(multi instanceof Agent);
        assertFalse(multi instanceof ProxyAgent);
        assertTrue(multi instanceof MultiTypeAgent);

        MultiTypeAgent multi_agent= (MultiTypeAgent) multi;

        Type[] types=new Type[]{Type.ELECTRONICS, Type.FOOD, Type.OTHER, Type.PHARMA};
        for(Type type:types) {
            Agent by_type = multi_agent.get(type);
            assertNotNull(by_type);
            assertTrue(by_type instanceof Agent);
            assertTrue(by_type instanceof ByTypeMarkupAgent);
            assertTrue(by_type instanceof ProxyAgent);

            Agent format = ((ProxyAgent) by_type).getDelegate();
            assertNotNull(format);
            assertTrue(format instanceof Agent);
            assertTrue(format instanceof FormatCurrencyAgent);
            assertTrue(format instanceof ProxyAgent);

            Agent print=((ProxyAgent) format).getDelegate();
            assertNotNull(print);
            assertTrue(print instanceof Agent);
            assertTrue(print instanceof PrintAgent);
        }
    }

    @Test
    public void testProductIntegration() {
        Context ctx = init();
        Agent agent = (Agent) ctx.get(Agent.class);

        /** sctx= // to capture request context */
        push(ctx, "1299.99", 3, Type.FOOD);
        Product p= (Product) ctx.get(Product.class);
        assertEquals("1591.58", p.markedUpPrice().toString());

        push(ctx, "5432.00", 1, Type.PHARMA);
        p= (Product) ctx.get(Product.class);
        assertEquals("6199.81", p.markedUpPrice().toString());

        push(ctx, "12456.95", 4, Type.OTHER);
        p= (Product) ctx.get(Product.class);
        assertEquals("13707.63", p.markedUpPrice().toString());
    }

    /** Just a place to put stuff that is accesible to agents */
    class Context extends HashMap {

    }

    class AgentException extends Exception {

        public AgentException(String s) {
            super(s);
        }
    }

    /** An execution unit that has access to its parameters through the context */
    interface Agent {
        public void execute(Context ctx) throws AgentException;
    }

    class NullAgent implements Agent {

        @Override
        public void execute(Context ctx) throws AgentException {
            // do nothing, you're the null agent
        }
    }

    /** Base class for agents to inherit from. A proxy agent does some processing
     * on the objects in the context then calls its delegate
     */
    class ProxyAgent implements Agent {

        protected Agent delegate;

        public ProxyAgent(Agent delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Context ctx) throws AgentException {
            getDelegate().execute(ctx);
        }

        public Agent getDelegate() { return delegate;}
    }

    /**
     * Agent that checks the context for a valid Product and Config. Having this at the top of the
     * pipeline we can avoid checking everything coming out of the context for being null
     */
    class CheckContextAgent extends ProxyAgent {

        public CheckContextAgent(Agent delegate) {
            super(delegate);
        }

        @Override
        public void execute(Context ctx) throws AgentException {
            Product product = (Product) ctx.get(Product.class);
            if(product == null) {
                throw new AgentException("Cannot find Product in context");
            }
            Config cfg = (Config) ctx.get(Config.class);
            if(cfg==null) {
                throw new AgentException("Cannot find Config in context");
            }

            super.execute(ctx);
        }
    }

    @Test
    public void testCheckContextAgent() {
        Context ctx=new Context();
        ctx.put(Agent.class, new CheckContextAgent(new NullAgent()));
        Agent agent = (Agent) ctx.get(Agent.class);

        try {
            agent.execute(ctx);
            fail("Should catch that no product is in context");
        } catch(AgentException e) { }

        ctx.put(Product.class, new Product().price("100").people(2).type(Type.OTHER));
        try {
            agent.execute(ctx);
            fail("Should catch that no product is in context");
        } catch(AgentException e) { }
    }


    /** Final agent in the pipeline, will print the Product on system.out. In a production system
     * this agent would save the Product/quote to the DB
     */
    class PrintAgent implements Agent {

        @Override
        public void execute(Context ctx) {
            Product prod = (Product) ctx.get(Product.class);
            System.out.println(prod);
        }
    }

    /** Agent that applies the flat markup directly on the initialPrice. Will also set up
     * the markedUpPrice to the value of initialPrice
     */
    class FlatMarkupAgent extends ProxyAgent{

        public FlatMarkupAgent(Agent delegate) {
            super(delegate);
        }

        @Override
        public void execute(Context ctx)  throws AgentException {
            Product product = (Product) ctx.get(Product.class);
            Config cfg = (Config) ctx.get(Config.class);

            product.initialPrice(product.initialPrice().add(product.initialPrice().multiply(cfg.FLAT_MARKUP)));
            product.markedUpPrice(product.initialPrice());

            super.execute(ctx);
        }
    }

    @Test
    public void testFlatMarkup() {
        // TODO: grab percentage for flat from Config

        Context ctx = init();
        ctx.put(Agent.class, new FlatMarkupAgent(new NullAgent()));
        push(ctx, "100", 0, Type.OTHER);
        Product p= (Product) ctx.get(Product.class);
        assertNotNull(p);

        assertEquals(105.0, Double.parseDouble(p.markedUpPrice().toString()), 0.00001);
    }

    /**
     * Applies the markup for people working on the job
     */
    class PeopleMarkupAgent extends ProxyAgent {

        public PeopleMarkupAgent(Agent delegate) {
            super(delegate);
        }

        @Override
        public void execute(Context ctx) throws AgentException {

            Product product = (Product) ctx.get(Product.class);
            Config cfg = (Config) ctx.get(Config.class);

            if(product.people()<0) {
                throw new AgentException("Number of people has to be positive");
            }

            product.markedUpPrice(product.markedUpPrice().add(
                    product.initialPrice().multiply(cfg.PEOPLE_MARKUP).multiply(new BigDecimal(product.people()))));

            super.execute(ctx);
        }
    }

    @Test
    public void testPeopleAgent() {
        // TODO: grab percentage for people markup from Config

        Context ctx = init();
        Agent agent=new PeopleMarkupAgent(new NullAgent());
        Product p=new Product().price("100").markedUpPrice("100").people(1).type(Type.OTHER);
        ctx.put(Product.class, p);

        try {
            agent.execute(ctx);
        } catch (AgentException e) {
            fail(e.getMessage());
        }

        assertNotNull(p);
        assertEquals(101.2, Double.parseDouble(p.markedUpPrice().toString()), 0.00001);

        p.people(2).markedUpPrice(p.initialPrice());
        try {
            agent.execute(ctx);
        } catch (AgentException e) {
            fail(e.getMessage());
        }

        assertEquals(102.4, Double.parseDouble(p.markedUpPrice().toString()), 0.00001);

        p.people(0).markedUpPrice(p.initialPrice());
        try {
            agent.execute(ctx);
        } catch (AgentException e) {
            fail(e.getMessage());
        }

        assertEquals(100.0, Double.parseDouble(p.markedUpPrice().toString()), 0.00001);

        p.people(-1).markedUpPrice(p.initialPrice());
        try {
            agent.execute(ctx);
            fail("People agent should not accept number of people <0");
        } catch (AgentException e) {
        }

    }

    /**
     * Forwards the request to the correct branch by type of request.
     */
    class MultiTypeAgent implements Agent {

        protected Map<Type, Agent> agents=new HashMap<Type, Agent>();

        public MultiTypeAgent add(Type type, Agent agent) {
            agents.put(type, agent);
            return this;
        }

        public Agent get(Type type) { return agents.get(type); }

        @Override
        public void execute(Context ctx) throws AgentException {
            Product prod = (Product) ctx.get(Product.class);
            Agent delegate = agents.get(prod.type());
            if(delegate!=null) {
                delegate.execute(ctx);
            }
        }
    }

    @Test
    public void testMulti() {

        // TODO: grab percentages in assertEquals from Config

        Context ctx = init();
        MultiTypeAgent agent=new MultiTypeAgent();
        agent.add(Type.ELECTRONICS, new ByTypeMarkupAgent(ctx, Type.ELECTRONICS, new NullAgent()));
        agent.add(Type.FOOD, new ByTypeMarkupAgent(ctx, Type.FOOD, new NullAgent()));
        agent.add(Type.PHARMA, new ByTypeMarkupAgent(ctx, Type.PHARMA, new NullAgent()));
        agent.add(Type.OTHER, new ByTypeMarkupAgent(ctx, Type.OTHER, new NullAgent()));

        // tests if products with different types are routed correctly

        Product p=new Product().price("100").markedUpPrice("100").people(1).type(Type.OTHER);
        ctx.put(Product.class, p);

        try {
            agent.execute(ctx);
        } catch (AgentException e) {
            fail(e.getMessage());
        }

        // nothing is marked up for OTHER type
        assertEquals(100.0, Double.parseDouble(p.markedUpPrice().toString()), 0.00001);

        p.type(Type.PHARMA).markedUpPrice(p.initialPrice());
        try {
            agent.execute(ctx);
        } catch (AgentException e) {
            fail(e.getMessage());
        }

        // 7.5% for PHARMA
        assertEquals(107.5, Double.parseDouble(p.markedUpPrice().toString()), 0.00001);

        p.type(Type.FOOD).markedUpPrice(p.initialPrice());
        try {
            agent.execute(ctx);
        } catch (AgentException e) {
            fail(e.getMessage());
        }

        // 13% for FOOD
        assertEquals(113.0, Double.parseDouble(p.markedUpPrice().toString()), 0.00001);

        p.type(Type.ELECTRONICS).markedUpPrice(p.initialPrice());
        try {
            agent.execute(ctx);
        } catch (AgentException e) {
            fail(e.getMessage());
        }

        // 2% for ELECTRONICS
        assertEquals(102.0, Double.parseDouble(p.markedUpPrice().toString()), 0.00001);

    }

    /** Uses a BigDecimal to remove the decimals beyond 2nd place. Uses the correct rounding */
    class FormatCurrencyAgent extends ProxyAgent {
        public FormatCurrencyAgent(Agent delegate) {
            super(delegate);
        }

        @Override
        public void execute(Context ctx) throws AgentException {
            Product prod = (Product) ctx.get(Product.class);
            BigDecimal bd=prod.markedUpPrice().setScale(2, RoundingMode.HALF_EVEN);
            prod.markedUpPrice(bd);

            super.execute(ctx);
        }
    }

    @Test
    public void testFormatCurrency() {
        Context ctx = init();
        Agent agent=new FormatCurrencyAgent(new NullAgent());
        Product p=new Product().price("100").markedUpPrice("100").people(1).type(Type.OTHER);
        ctx.put(Product.class, p);

        try {
            agent.execute(ctx);
        } catch (AgentException e) {
            fail(e.getMessage());
        }

        assertNotNull(p);
        assertEquals("100.00", p.markedUpPrice().toString());

        p.markedUpPrice("100.5088");

        try {
            agent.execute(ctx);
        } catch (AgentException e) {
            fail(e.getMessage());
        }

        assertNotNull(p);
        assertEquals("100.51", p.markedUpPrice().toString());

        p.markedUpPrice("100.504999");

        try {
            agent.execute(ctx);
        } catch (AgentException e) {
            fail(e.getMessage());
        }

        assertNotNull(p);
        assertEquals("100.50", p.markedUpPrice().toString());
    }

    /**
     * An interesting agent that uses a passed in function to apply a markup that depends on the
     * type of product. The function codes applying the percentage to the markedUpPrice. While I could have had 4
     * agents each applying the correct markup for a type of product, the difference between them was minimal
     * and I decided to abstract the computation in a Function
     */
    class ByTypeMarkupAgent extends ProxyAgent {

        private final Function<BigDecimal> byTypeFunction;

        public ByTypeMarkupAgent(Context ctx, Type type, Agent delegate) {
            super(delegate);
            Config config = (Config) ctx.get(Config.class);
            this.byTypeFunction = config.getMarkupByType(type);
        }

        @Override
        public void execute(Context ctx) throws AgentException {

            Product prod = (Product) ctx.get(Product.class);
            prod.markedUpPrice(prod.markedUpPrice().add(byTypeFunction.f(ctx, prod.initialPrice())));

            getDelegate().execute(ctx);
        }
    }

    /**
     * Generic interface for a function with a single argument
     * @param <T>
     */
    interface Function<T> {
        public T f(Context ctx, T value);
    }

    /** A markup function receives the percentage as a parameter to the constructor, then on processing
     * multiplies the percentage to the value passes in and returns the result.
     */
    class MarkupFunction implements Function<BigDecimal> {
        private final BigDecimal markup;

        public MarkupFunction(BigDecimal markup) {
            this.markup = markup;
        }

        @Override
        public BigDecimal f(Context ctx, BigDecimal value) {
            return value.multiply(markup);
        }

        public BigDecimal getMarkup() { return markup; }
    };

    @Test
    public void testMarkupFunction()
    {
        Context ctx=init();

        MarkupFunction f=new MarkupFunction(new BigDecimal("12.34567"));
        assertNotNull(f.getMarkup());
        assertEquals("12.34567", f.getMarkup().toString());

        f=new MarkupFunction(new BigDecimal("2.5"));
        BigDecimal v=f.f(ctx, new BigDecimal("4"));
        assertNotNull(v);
        assertEquals(10.0, Double.parseDouble(v.toString()), 0.00001);
        v=f.f(ctx, new BigDecimal("4.5"));
        assertNotNull(v);
        assertEquals(2.5*4.5, Double.parseDouble(v.toString()), 0.00001);
    }

    /**
     * Generic config class. In a production system, you can build a Config class that loads all configuration
     * from the database and builds the markup function dynamically.
     */
    class Config {
        public final BigDecimal FLAT_MARKUP = new BigDecimal("0.05");
        public final BigDecimal PEOPLE_MARKUP = new BigDecimal("0.012");
        public final BigDecimal FOOD_MARKUP = new BigDecimal("0.13");
        public final BigDecimal PHARMA_MARKUP = new BigDecimal("0.075");
        public final BigDecimal ELECTRONICS_MARKUP = new BigDecimal("0.02");

        public Function<BigDecimal> applyFoodMarkup = new MarkupFunction(FOOD_MARKUP);
        public Function<BigDecimal> applyPharmaMarkup = new MarkupFunction(PHARMA_MARKUP);
        public Function<BigDecimal> applyElectronicsMarkup = new MarkupFunction(ELECTRONICS_MARKUP);
        public Function<BigDecimal> applyOtherMarkup = new MarkupFunction(new BigDecimal("0.0"));

        /** Helper function to be used during init to setup the agent pipeline */
        public Function<BigDecimal> getMarkupByType(Type type) {
            if(type==null) {
                return applyOtherMarkup;
            }

            switch(type) {
                case PHARMA:
                    return applyPharmaMarkup;
                case FOOD:
                    return applyFoodMarkup;
                case ELECTRONICS:
                    return applyElectronicsMarkup;
                case OTHER:
                default:
                    return applyOtherMarkup;
            }
        }

    }

    @Test
    public void testGetMarkupByType() {
        Config cfg = new Config();

        assertEquals(cfg.applyOtherMarkup, cfg.getMarkupByType(null));
        assertEquals(cfg.applyPharmaMarkup, cfg.getMarkupByType(Type.PHARMA));
        assertEquals(cfg.applyFoodMarkup, cfg.getMarkupByType(Type.FOOD));
        assertEquals(cfg.applyElectronicsMarkup, cfg.getMarkupByType(Type.ELECTRONICS));
        assertEquals(cfg.applyOtherMarkup, cfg.getMarkupByType(Type.OTHER));
    }

    /** Types of jobs */
    enum Type {
        PHARMA,
        ELECTRONICS,
        FOOD,
        OTHER
    }

    /** A Product to be marked up / a Job */
    class Product {
        /** Initial introduced price. Only kept to be displayed at the end; we don't use it during processing */
        BigDecimal price=new BigDecimal(0);
        /** Initial price + flat markups, to be used for future markups */
        BigDecimal initialPrice=new BigDecimal(0);
        /** Marked up price at one moment */
        BigDecimal markedUpPrice=new BigDecimal(0);
        /** Type of the job */
        Type type=Type.OTHER;
        /** Number of people involved */
        int people=0;

        public Product() {

        }

        public Product price(String price) {
            this.price = new BigDecimal(price);
            this.initialPrice(price);
            return this;
        }

        public Product initialPrice(String price) {
            this.initialPrice=new BigDecimal(price);
            return this;
        }

        public Product initialPrice(BigDecimal price) {
            this.initialPrice=price;
            return this;
        }

        public Product markedUpPrice(String price) {
            this.markedUpPrice=new BigDecimal(price);
            return this;
        }

        public Product markedUpPrice(BigDecimal price) {
            this.markedUpPrice=price;
            return this;
        }

        public Product type(Type type) {
            this.type=type;
            return this;
        }

        public Product people(int people) {
            this.people=people;
            return this;
        }

        public BigDecimal price() { return price; }
        public BigDecimal initialPrice() { return initialPrice; }
        public BigDecimal markedUpPrice() { return markedUpPrice; }
        public Type type() { return type; }
        public int people() { return people; }

        public String toString() {
            StringBuilder b= new StringBuilder("Product{");
            b.append("Type: ");
            b.append(type());
            b.append(", Price: ");
            b.append(price());
            b.append(", People: ");
            b.append(people());
            b.append(", MarkedupPrice: ");
            b.append(markedUpPrice());
            b.append("}");
            return b.toString();
        }
    }

    @Test
    public void testProduct() {
        Product prod=new Product();
        assertEquals("0", prod.initialPrice().toString());
        assertEquals("0", prod.markedUpPrice().toString());
        assertEquals(Type.OTHER, prod.type());
        assertEquals(0, prod.people());

        prod.initialPrice("1299.99").people(3).type(Type.FOOD).markedUpPrice("1508.76");
        assertEquals("1299.99", prod.initialPrice().toString());
        assertEquals(3, prod.people());
        assertEquals(Type.FOOD, prod.type());
        assertEquals("1508.76", prod.markedUpPrice().toString());
    }
}

/** Notes:
 * - I shouldn't have put all these classes in the same file but it is just a test program in which the majority
 *   of the classes have dummy implementations, so for ease of parsing, I put them all in the same place
 * - There are many more tests that can be written, but for the sake of keeping things small I tested just the
 *   obvious things

 */