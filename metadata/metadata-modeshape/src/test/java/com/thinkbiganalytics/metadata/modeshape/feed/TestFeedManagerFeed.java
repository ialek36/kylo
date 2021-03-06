package com.thinkbiganalytics.metadata.modeshape.feed;

/*-
 * #%L
 * thinkbig-metadata-modeshape
 * %%
 * Copyright (C) 2017 ThinkBig Analytics
 * %%
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
 * #L%
 */

import com.thinkbiganalytics.metadata.api.MetadataAccess;
import com.thinkbiganalytics.metadata.api.category.Category;
import com.thinkbiganalytics.metadata.api.category.CategoryProvider;
import com.thinkbiganalytics.metadata.api.datasource.Datasource;
import com.thinkbiganalytics.metadata.api.datasource.DatasourceProvider;
import com.thinkbiganalytics.metadata.api.datasource.DerivedDatasource;
import com.thinkbiganalytics.metadata.api.feed.Feed;
import com.thinkbiganalytics.metadata.api.feed.FeedDestination;
import com.thinkbiganalytics.metadata.api.feed.FeedProvider;
import com.thinkbiganalytics.metadata.api.feedmgr.category.FeedManagerCategoryProvider;
import com.thinkbiganalytics.metadata.api.feedmgr.feed.FeedManagerFeed;
import com.thinkbiganalytics.metadata.api.feedmgr.feed.FeedManagerFeedProvider;
import com.thinkbiganalytics.metadata.api.feedmgr.template.FeedManagerTemplate;
import com.thinkbiganalytics.metadata.api.feedmgr.template.FeedManagerTemplateProvider;
import com.thinkbiganalytics.metadata.api.feedmgr.template.TemplateDeletionException;
import com.thinkbiganalytics.metadata.modeshape.JcrMetadataAccess;
import com.thinkbiganalytics.metadata.modeshape.JcrPropertyTest;
import com.thinkbiganalytics.metadata.modeshape.JcrTestConfig;
import com.thinkbiganalytics.metadata.modeshape.ModeShapeEngineConfig;
import com.thinkbiganalytics.metadata.modeshape.security.AdminCredentials;
import com.thinkbiganalytics.support.FeedNameUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.testng.Assert;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

/**
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {ModeShapeEngineConfig.class, JcrTestConfig.class, FeedTestConfig.class})
@ComponentScan(basePackages = {"com.thinkbiganalytics.metadata.modeshape"})
public class TestFeedManagerFeed {

    private static final Logger log = LoggerFactory.getLogger(TestFeedManagerFeed.class);

    @Inject
    CategoryProvider categoryProvider;

    @Inject
    FeedProvider feedProvider;

    @Inject
    FeedManagerFeedProvider feedManagerFeedProvider;

    @Inject
    FeedManagerTemplateProvider feedManagerTemplateProvider;

    @Inject
    FeedManagerCategoryProvider feedManagerCategoryProvider;

    @Inject
    private DatasourceProvider datasourceProvider;

    @Inject
    private JcrMetadataAccess metadata;

    @Inject
    private FeedTestUtil feedTestUtil;


    private boolean deleteTemplate(String templateName) {
        //try to delete the template.  This should fail since there are feeds attached to it
        return metadata.commit(new AdminCredentials(), () -> {
            FeedManagerTemplate template = feedTestUtil.findOrCreateTemplate(templateName);
            return feedManagerTemplateProvider.deleteTemplate(template.getId());

        });
    }

    private void setupFeedAndTemplate(String categorySystemName, String feedName, String templateName) {
        //first create the category
        metadata.commit(new AdminCredentials(), () -> {
            Category category = feedTestUtil.findOrCreateCategory(categorySystemName);
            return category.getId();
        });

        //creqte the feed
        metadata.commit(new AdminCredentials(), () -> {
            FeedManagerFeed feed = feedTestUtil.findOrCreateFeed(categorySystemName, feedName, templateName);
            return feed.getId();
        });

        //ensure the feed relates to the template
        metadata.read(new AdminCredentials(), () -> {
            FeedManagerTemplate template = feedTestUtil.findOrCreateTemplate(templateName);
            List<FeedManagerFeed> feeds = template.getFeeds();
            Assert.assertTrue(feeds != null && feeds.size() > 0);
        });
    }

    /**
     * Test querying a large number of feeds
     */
    @Test
    public void testLotsOfFeeds() {
        //increase to query more .. i.e. 1000
        int numberOfFeeds = 5;

        int numberOfFeedsPerCategory = 20;
        String templateName = "my_template";

        int categories = numberOfFeeds / numberOfFeedsPerCategory;
        //create all the categories
        metadata.commit(new AdminCredentials(), () -> {
            for (int i = 1; i <= categories; i++) {
                Category category = feedTestUtil.createCategory("category_" + i);
            }
        });

        metadata.commit(new AdminCredentials(), () -> {
            FeedManagerTemplate template = feedTestUtil.findOrCreateTemplate(templateName);
        });

        //create the feeds
        metadata.commit(new AdminCredentials(), () -> {

            FeedManagerTemplate template = feedTestUtil.findOrCreateTemplate(templateName);
            Category category = null;

            int categoryNum = 0;
            String categoryName = "category" + categoryNum;
            for (int i = 0; i < numberOfFeeds; i++) {
                if (i % numberOfFeedsPerCategory == 0) {
                    categoryNum++;
                    categoryName = "category_" + categoryNum;
                    category = feedTestUtil.findOrCreateCategory(categoryName);
                }
                FeedManagerFeed feed = feedTestUtil.findOrCreateFeed(category, "feed_" + i, template);
            }
        });

        //now query it
        long time = System.currentTimeMillis();

        Integer size = metadata.read(new AdminCredentials(), () -> {
            List<FeedManagerFeed> feeds = feedManagerFeedProvider.findAll();
            return feeds.size();
        });
        long stopTime = System.currentTimeMillis();
        log.info("Time to query {} feeds was {} ms", size, (stopTime - time));

    }


    @Test
    public void testFeedDatasource() {
        String categorySystemName = "my_category";
        String feedName = "my_feed";
        String templateName = "my_template";
        String description = " my feed description";
        setupFeedAndTemplate(categorySystemName, feedName, templateName);
//        boolean isDefineTable = true;
//        boolean isGetFile = false;

        metadata.commit(() -> {

            Feed feed = feedTestUtil.findFeed(categorySystemName, feedName);

            Set<Datasource.ID> sources = new HashSet<Datasource.ID>();
            Set<com.thinkbiganalytics.metadata.api.datasource.Datasource.ID> destinations = new HashSet<>();
            //Add Table Dependencies
            String uniqueName = FeedNameUtil.fullName(categorySystemName, feedName);

            DerivedDatasource srcDatasource = datasourceProvider.ensureDatasource(uniqueName, feed.getDescription(), DerivedDatasource.class);
            sources.add(srcDatasource.getId());
            DerivedDatasource destDatasource = datasourceProvider.ensureDatasource("destination", feed.getDescription(), DerivedDatasource.class);
            destinations.add(destDatasource.getId());

            sources.stream().forEach(sourceId -> feedProvider.ensureFeedSource(feed.getId(), sourceId));
            destinations.stream().forEach(destinationId -> feedProvider.ensureFeedDestination(feed.getId(), destinationId));
        }, MetadataAccess.SERVICE);

        //ensure the sources and dest got created
        metadata.read(() -> {
            Feed feed = feedTestUtil.findFeed(categorySystemName, feedName);
            Assert.assertNotNull(feed.getSources());
            Assert.assertTrue(feed.getSources().size() == 1, "Feed Sources should be 1");

            Assert.assertNotNull(feed.getDestinations());
            Assert.assertTrue(feed.getDestinations().size() == 1, "Feed Destinations should be 1");

            List<FeedDestination> feedDestinations = feed.getDestinations();
            if (feedDestinations != null) {
                FeedDestination feedDestination = feedDestinations.get(0);
                Datasource ds = feedDestination.getDatasource();
                Assert.assertTrue(ds instanceof DerivedDatasource, "Datasource was not expected DerivedDatasource");
            }


        }, MetadataAccess.SERVICE);

    }

    @Test
    public void testFeedTemplates() {
        String categorySystemName = "my_category";
        String feedName = "my_feed";
        String templateName = "my_template";
        setupFeedAndTemplate(categorySystemName, feedName, templateName);

        //try to delete the template.  This should fail since there are feeds attached to it
        Boolean deleteStatus = null;
        try {
            deleteStatus = deleteTemplate(templateName);
        } catch (TemplateDeletionException e) {
            Assert.assertNotNull(e);
            deleteStatus = false;
        }
        //assert that we could not delete it
        Assert.assertFalse(deleteStatus);

        //try to delete the feed
        metadata.commit(new AdminCredentials(), () -> {
            FeedManagerFeed feed = feedTestUtil.findFeed(categorySystemName, feedName);
            Assert.assertNotNull(feed);
            if (feed != null) {
                feedManagerFeedProvider.delete(feed);
            }
        });

        //ensure it is deleted
        metadata.read(new AdminCredentials(), () -> {
            FeedManagerFeed feed = feedTestUtil.findFeed(categorySystemName, feedName);
            Assert.assertNull(feed);
        });

        //try to delete the template.  This should succeed since the feeds are gone
        deleteStatus = deleteTemplate(templateName);
        Assert.assertEquals(deleteStatus.booleanValue(), true);


    }


}
