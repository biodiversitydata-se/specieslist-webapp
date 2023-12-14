/*
 * Copyright (C) 2022 Atlas of Living Australia
 * All Rights Reserved.
 *
 * The contents of this file are subject to the Mozilla Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 */
package au.org.ala.specieslist

class SpeciesListKVP implements Comparable {
    String key
    String value
    String vocabValue
    String dataResourceUid
    Integer itemOrder
    static belongsTo = SpeciesListItem
    static constraints = {
        vocabValue(nullable:true)
        itemOrder(nullable:true)
        value(nullable:true)
    }
    static mapping ={
        key column: 'the_key', index: 'idx_key'
        value column: "value", sqlType: "TEXT"
        dataResourceUid index: 'idx_data_resource_uid'
        itemOrder index: 'idx_item_order'
    }

    public int compareTo(def other){
        if(itemOrder == 0 && other?.itemOrder == 0)
            return key <=> other?.key
        return itemOrder <=> other?.itemOrder //<=> Groovy compare to operator
    }
}
