/*
 * Copyright 2006 Amazon Technologies, Inc. or its affiliates.
 * Amazon, Amazon.com and Carbonado are trademarks or registered trademarks
 * of Amazon Technologies, Inc. or its affiliates.  All rights reserved.
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

package com.amazon.carbonado.layout;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;

import org.cojen.util.SoftValuedHashMap;

import com.amazon.carbonado.Cursor;
import com.amazon.carbonado.FetchException;
import com.amazon.carbonado.FetchNoneException;
import com.amazon.carbonado.PersistException;
import com.amazon.carbonado.Query;
import com.amazon.carbonado.Storable;
import com.amazon.carbonado.SupportException;
import com.amazon.carbonado.info.StorableInfo;
import com.amazon.carbonado.info.StorableProperty;
import com.amazon.carbonado.synthetic.SyntheticKey;
import com.amazon.carbonado.synthetic.SyntheticProperty;
import com.amazon.carbonado.synthetic.SyntheticStorableBuilder;
import com.amazon.carbonado.util.AnnotationDescPrinter;

/**
 * Describes the layout of a specific generation of a storable.
 *
 * @author Brian S O'Neill
 * @see LayoutFactory
 */
public class Layout {
    private static Map<Long, Class<? extends Storable>> cReconstructed;

    static {
        cReconstructed = Collections.synchronizedMap(new SoftValuedHashMap());
    }

    static Class<? extends Storable> reconstruct(final Layout layout, ClassLoader loader)
        throws FetchException, SupportException
    {
        synchronized (cReconstructed) {
            Long key = layout.getLayoutID();
            Class<? extends Storable> clazz = cReconstructed.get(key);
            if (clazz != null) {
                return clazz;
            }

            SyntheticStorableBuilder builder =
                new SyntheticStorableBuilder(layout.getStorableTypeName(), loader);

            // Make sure reconstructed class encodes the same as before.
            builder.setEvolvable(true);

            builder.setClassNameProvider(new SyntheticStorableBuilder.ClassNameProvider() {
                public String getName() {
                    return layout.getStorableTypeName();
                }

                // The name of the auto-generated class should not be made with
                // createExplicit. Otherwise, property type changes will
                // conflict, since the reconstructed class name is the same.
                public boolean isExplicit() {
                    return false;
                }
            });

            SyntheticKey primaryKey = builder.addPrimaryKey();

            for (LayoutProperty property : layout.getAllProperties()) {
                Class propClass = property.getPropertyType(loader);
                if (Query.class.isAssignableFrom(propClass) ||
                    Storable.class.isAssignableFrom(propClass)) {
                    // Accidentally stored join property in layout, caused by a
                    // bug in a previous version of Layout. Move on.
                    continue;
                }

                SyntheticProperty synthProp =
                    builder.addProperty(property.getPropertyName(), propClass);

                synthProp.setIsNullable(property.isNullable());
                synthProp.setIsVersion(property.isVersion());

                if (property.isPrimaryKeyMember()) {
                    primaryKey.addProperty(property.getPropertyName());
                }

                if (property.getAdapterTypeName() != null) {
                    String desc = property.getAdapterParams();
                    if (desc == null) {
                        desc = AnnotationDescPrinter
                            .makePlainDescriptor(property.getAdapterTypeName());
                    }
                    synthProp.addAccessorAnnotationDescriptor(desc);
                }
            }

            clazz = builder.build();

            cReconstructed.put(key, clazz);
            return clazz;
        }
    }

    private final LayoutFactory mLayoutFactory;
    private final StoredLayout mStoredLayout;

    private transient List<LayoutProperty> mAllProperties;

    /**
     * Creates a Layout around an existing storable.
     */
    Layout(LayoutFactory factory, StoredLayout storedLayout) {
        mLayoutFactory = factory;
        mStoredLayout = storedLayout;
    }

    /**
     * Copies layout information into freshly prepared storables. Call insert
     * (on this class) to persist them.
     */
    Layout(LayoutFactory factory, StorableInfo<?> info, long layoutID) {
        mLayoutFactory = factory;

        StoredLayout storedLayout = factory.mLayoutStorage.prepare();
        mStoredLayout = storedLayout;

        storedLayout.setLayoutID(layoutID);
        storedLayout.setStorableTypeName(info.getStorableType().getName());
        storedLayout.setCreationTimestamp(System.currentTimeMillis());
        try {
            storedLayout.setCreationUser(System.getProperty("user.name"));
        } catch (SecurityException e) {
            // Can't get user, no big deal.
        }
        try {
            storedLayout.setCreationHost(InetAddress.getLocalHost().getHostName());
        } catch (UnknownHostException e) {
            // Can't get host, no big deal.
        } catch (SecurityException e) {
            // Can't get host, no big deal.
        }

        Collection<? extends StorableProperty<?>> properties = info.getAllProperties().values();
        List<LayoutProperty> list = new ArrayList<LayoutProperty>(properties.size());
        int ordinal = 0;
        for (StorableProperty<?> property : properties) {
            if (property.isJoin()) {
                continue;
            }
            StoredLayoutProperty storedLayoutProperty = mLayoutFactory.mPropertyStorage.prepare();
            list.add(new LayoutProperty(storedLayoutProperty, property, layoutID, ordinal));
            ordinal++;
        }

        mAllProperties = Collections.unmodifiableList(list);
    }

    /**
     * Returns a unique identifier for this layout.
     */
    public long getLayoutID() {
        return mStoredLayout.getLayoutID();
    }

    /**
     * Storable type name is a fully qualified Java class name.
     */
    public String getStorableTypeName() {
        return mStoredLayout.getStorableTypeName();
    }

    /**
     * Returns the generation of this layout, where zero represents the first
     * generation.
     */
    public int getGeneration() {
        return mStoredLayout.getGeneration();
    }

    /**
     * Returns all the non-primary key properties of this layout, in their
     * proper order.
     */
    public List<LayoutProperty> getDataProperties() throws FetchException {
        List<LayoutProperty> all = getAllProperties();
        List<LayoutProperty> data = new ArrayList<LayoutProperty>(all.size() - 1);
        for (LayoutProperty property : all) {
            if (!property.isPrimaryKeyMember()) {
                data.add(property);
            }
        }
        return Collections.unmodifiableList(data);
    }

    /**
     * Returns all the properties of this layout, in their proper order.
     */
    public List<LayoutProperty> getAllProperties() throws FetchException {
        if (mAllProperties == null) {
            Cursor <StoredLayoutProperty> cursor = mStoredLayout.getProperties()
                .orderBy("ordinal")
                .fetch();

            List<LayoutProperty> list = new ArrayList<LayoutProperty>();

            while (cursor.hasNext()) {
                list.add(new LayoutProperty(cursor.next()));
            }

            mAllProperties = Collections.unmodifiableList(list);
        }

        return mAllProperties;
    }

    /**
     * Returns the date and time for when this layout generation was created.
     */
    public DateTime getCreationDateTime() {
        return new DateTime(mStoredLayout.getCreationTimestamp());
    }

    /**
     * Returns the user that created this layout generation.
     */
    public String getCreationUser() {
        return mStoredLayout.getCreationUser();
    }

    /**
     * Returns the host machine that created this generation.
     */
    public String getCreationHost() {
        return mStoredLayout.getCreationHost();
    }

    /**
     * Returns the layout for a particular generation of this layout's type.
     *
     * @throws FetchNoneException if generation not found
     */
    public Layout getGeneration(int generation) throws FetchNoneException, FetchException {
        StoredLayout storedLayout = mLayoutFactory.mLayoutStorage
            .query("storableTypeName = ? & generation = ?")
            .with(getStorableTypeName()).with(generation)
            .loadOne();
        return new Layout(mLayoutFactory, storedLayout);
    }

    /**
     * Returns the previous known generation of the storable's layout, or null
     * if none.
     *
     * @return a layout with a lower generation, or null if none
     */
    public Layout previousGeneration() throws FetchException {
        Cursor<StoredLayout> cursor = mLayoutFactory.mLayoutStorage
            .query("storableTypeName = ? & generation < ?")
            .with(getStorableTypeName()).with(getGeneration())
            .orderBy("-generation")
            .fetch();

        try {
            if (cursor.hasNext()) {
                return new Layout(mLayoutFactory, cursor.next());
            }
        } finally {
            cursor.close();
        }

        return null;
    }

    /**
     * Returns the next known generation of the storable's layout, or null
     * if none.
     *
     * @return a layout with a higher generation, or null if none
     */
    public Layout nextGeneration() throws FetchException {
        Cursor<StoredLayout> cursor =
            mLayoutFactory.mLayoutStorage.query("storableTypeName = ? & generation > ?")
            .with(getStorableTypeName()).with(getGeneration())
            .orderBy("+generation")
            .fetch();

        try {
            if (cursor.hasNext()) {
                return new Layout(mLayoutFactory, cursor.next());
            }
        } finally {
            cursor.close();
        }

        return null;
    }

    /**
     * Reconstructs the storable type defined by this layout by returning an
     * auto-generated class. The reconstructed storable type will not contain
     * everything in the original, but rather the minimum required to decode
     * persisted instances.
     */
    public Class<? extends Storable> reconstruct() throws FetchException, SupportException {
        return reconstruct(null);
    }

    /**
     * Reconstructs the storable type defined by this layout by returning an
     * auto-generated class. The reconstructed storable type will not contain
     * everything in the original, but rather the minimum required to decode
     * persisted instances.
     *
     * @param loader optional ClassLoader to load reconstruct class into, if it
     * has not been loaded yet
     */
    public Class<? extends Storable> reconstruct(ClassLoader loader)
        throws FetchException, SupportException
    {
        Class<? extends Storable> reconstructed = reconstruct(this, loader);
        mLayoutFactory.registerReconstructed(reconstructed, this);
        return reconstructed;
    }

    /**
     * Returns true if the given layout matches this one. Layout ID,
     * generation, and creation info is not considered in the comparison.
     */
    public boolean equalLayouts(Layout layout) throws FetchException {
        if (this == layout) {
            return true;
        }
        return getStorableTypeName().equals(layout.getStorableTypeName())
            && getAllProperties().equals(layout.getAllProperties());
    }

    // Assumes caller is in a transaction.
    void insert(int generation) throws PersistException {
        if (mAllProperties == null) {
            throw new IllegalStateException();
        }
        mStoredLayout.setGeneration(generation);
        mStoredLayout.insert();
        for (LayoutProperty property : mAllProperties) {
            property.insert();
        }
    }
}